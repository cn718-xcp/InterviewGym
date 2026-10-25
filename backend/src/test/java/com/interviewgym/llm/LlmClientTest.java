package com.interviewgym.llm;

import com.interviewgym.config.LlmProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用 JDK 自带 HttpServer 起本地端点做真请求测试（零新依赖）。
 * 注：不用 MockRestServiceServer——它会与构造器中的 requestFactory(超时设置) 互相覆盖（详见 docs/AI-FAILURES.md #1）。
 */
class LlmClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private volatile int respondStatus = 200;
    private volatile String respondBody = "{}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/paas/v4";
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            lastRequestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = respondBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(respondStatus, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private LlmProperties props() {
        LlmProperties props = new LlmProperties();
        props.setBaseUrl(baseUrl);
        props.setApiKey("test-key");
        props.setModel("test-model");
        return props;
    }

    private LlmClient client(LlmProperties props) {
        return new LlmClient(props, org.springframework.web.client.RestClient.builder());
    }

    @Test
    void chatReturnsReplyContent() {
        respondBody = """
                {"id":"cmpl-1","choices":[{"message":{"role":"assistant","content":"模拟面试开始"},"finish_reason":"stop"}],"usage":{"total_tokens":42}}
                """;

        String content = client(props()).chat("你是面试官", "出一题");

        assertThat(content).isEqualTo("模拟面试开始");
        String requestBody = lastRequestBody.get();
        assertThat(requestBody)
                .contains("\"model\":\"test-model\"")
                .contains("\"temperature\":0.3")
                .contains("\"role\":\"system\"")
                .contains("\"role\":\"user\"");
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer test-key");
    }

    @Test
    void chatThrowsLlmExceptionOnServerErrorStatus() {
        respondStatus = 500;
        respondBody = "{\"error\":{\"message\":\"internal\"}}";

        assertThatThrownBy(() -> client(props()).chat("system", "user"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining("test-key");
    }

    @Test
    void chatThrowsLlmExceptionOn4xxAuthError() {
        respondStatus = 401;
        respondBody = "{\"error\":{\"message\":\"invalid api key\"}}";

        assertThatThrownBy(() -> client(props()).chat("system", "user"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("test-key");
    }

    @Test
    void chatNormalizesTrailingSlashInBaseUrl() {
        LlmProperties props = props();
        props.setBaseUrl(baseUrl + "/");
        respondBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";

        assertThat(client(props).chat("system", "user")).isEqualTo("ok");
    }

    @Test
    void chatThrowsLlmExceptionOnTimeout() throws IOException {
        // 端点只接受连接、永不响应 —— 读超时（1s）必然触发，确定性模拟超时
        try (ServerSocket silentSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            LlmProperties props = props();
            props.setBaseUrl("http://127.0.0.1:" + silentSocket.getLocalPort() + "/api/paas/v4");
            props.setReadTimeoutSeconds(1);

            assertThatThrownBy(() -> client(props).chat("system", "user"))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("不可达或超时");
        }
    }

    @Test
    void chatThrowsWithoutRequestWhenEndpointNotConfigured() {
        LlmProperties props = props();
        props.setBaseUrl("");
        props.setApiKey("");

        assertThatThrownBy(() -> client(props).chat("system", "user"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("LLM_BASE_URL");

        // 未配置时不得发出任何 HTTP 请求（spec: llm-client.md 不变式）
        assertThat(lastRequestBody.get()).isNull();
    }

    @Test
    void chatThrowsOnBlankPrompt() {
        assertThatThrownBy(() -> client(props()).chat(" ", "user"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chatThrowsWhenResponseHasNoContent() {
        respondBody = """
                {"choices":[{"message":{"role":"assistant"}}]}
                """;

        assertThatThrownBy(() -> client(props()).chat("system", "user"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("返回内容为空");
    }

    @Test
    void embedReturnsVectorsAlignedByIndex() {
        LlmProperties props = props();
        props.setEmbeddingModel("embedding-3");
        respondBody = """
                {"data":[{"index":1,"embedding":[0.2,0.4]},{"index":0,"embedding":[0.1,0.3]}]}
                """;

        List<float[]> vectors = client(props).embed(List.of("文本一", "文本二"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.3f);
        assertThat(vectors.get(1)).containsExactly(0.2f, 0.4f);
        assertThat(lastRequestBody.get())
                .contains("\"model\":\"embedding-3\"")
                .contains("\"input\"");
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer test-key");
    }

    @Test
    void embedThrowsIllegalStateWithoutRequestWhenEmbeddingModelUnconfigured() {
        assertThatThrownBy(() -> client(props()).embed(List.of("文本")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_EMBEDDING_MODEL");

        // 配置缺失时不得发出任何 HTTP 请求（spec: llm-client.md 不变式）
        assertThat(lastRequestBody.get()).isNull();
    }

    @Test
    void embedThrowsOnEmptyInput() {
        LlmProperties props = props();
        props.setEmbeddingModel("embedding-3");

        assertThatThrownBy(() -> client(props).embed(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void embedThrowsLlmExceptionOnServerErrorStatus() {
        LlmProperties props = props();
        props.setEmbeddingModel("embedding-3");
        respondStatus = 500;
        respondBody = "{\"error\":{\"message\":\"internal\"}}";

        assertThatThrownBy(() -> client(props).embed(List.of("文本")))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining("test-key");
    }

    @Test
    void embedThrowsWhenResponseCountMismatch() {
        LlmProperties props = props();
        props.setEmbeddingModel("embedding-3");
        respondBody = "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}";  // 2 入 1 出

        assertThatThrownBy(() -> client(props).embed(List.of("文本一", "文本二")))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("不对应");
    }
}
