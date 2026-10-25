package com.interviewgym.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.interviewgym.config.LlmProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OpenAI 兼容 chat/completions 与 embeddings 的最小客户端（D005 可配置端点、D007 自写 RestClient、D011 嵌入，spec: docs/specs/llm-client.md）。
 * 只负责一次可靠调用并返回文本/向量；JSON 解析与修复重试属 core/ 业务层。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    /** 出题与评估要稳定不要发散（spec 固定值，不得调高） */
    private static final double TEMPERATURE = 0.3;
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private final LlmProperties props;
    private final RestClient restClient;

    public LlmClient(LlmProperties props, RestClient.Builder builder) {
        this.props = props;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(props.getReadTimeoutSeconds() * 1_000);
        this.restClient = builder.requestFactory(requestFactory).build();
    }

    /** 启动自检：配置问题（未配 key / base-url 拼错）在第一发请求前就该可见，而不是等用户撞 502 */
    @PostConstruct
    void logStartupConfig() {
        log.info("LLM 客户端就绪：base-url=[{}], model=[{}], api-key={}, 读超时={}s；embedding：model=[{}], base-url=[{}], api-key={}",
                props.getBaseUrl(), props.getModel(),
                notBlank(props.getApiKey()) ? "已配置" : "未配置", props.getReadTimeoutSeconds(),
                notBlank(props.getEmbeddingModel()) ? props.getEmbeddingModel() : "未配置（简历 RAG 不可用）",
                notBlank(props.getEmbeddingBaseUrl()) ? props.getEmbeddingBaseUrl() + "（独立）" : "复用 chat 端点",
                notBlank(props.getEmbeddingApiKey()) ? "已配置（独立）" : "复用 chat 端点");
    }

    /**
     * 单轮对话调用，返回模型回复文本。
     *
     * @throws IllegalArgumentException prompt 为空
     * @throws LlmException             配置缺失 / 端点不可达或超时 / 错误状态码 / 返回内容为空
     */
    public String chat(String systemPrompt, String userPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank() || userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("system 与 user prompt 均不得为空");
        }
        if (!configured()) {
            throw new LlmException("LLM 端点未配置，请设置环境变量 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL");
        }
        Map<String, Object> requestBody = Map.of(
                "model", props.getModel(),
                "temperature", TEMPERATURE,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        try {
            ChatCompletionResponse response = restClient.post()
                    .uri(endpointUrl(props.getBaseUrl(), "/chat/completions"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .body(requestBody)
                    .retrieve()
                    .body(ChatCompletionResponse.class);
            return requireContent(response);
        } catch (RestClientResponseException e) {
            throw new LlmException("LLM 端点返回错误状态：" + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            // Spring 对 IO 类失败（连接拒绝、读超时）的包装类型不统一，按根因分类
            String message = hasIoCause(e) ? "LLM 端点不可达或超时" : "LLM 响应处理失败";
            throw new LlmException(message, e);
        }
    }

    /**
     * 批量向量化：POST /embeddings，按 data[].index 对齐，返回与输入等长的向量列表（v2，D011）。
     * 端点独立可配（D013）：embedding-base-url / embedding-api-key 缺省时回落 chat 端点。
     *
     * @throws IllegalArgumentException 输入列表为空
     * @throws IllegalStateException   嵌入模型未配置（配置缺失，区别于端点故障；调用方据此转 400）
     * @throws LlmException            端点未配置 / 不可达或超时 / 错误状态码 / 响应与输入不对应
     */
    public List<float[]> embed(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("embed 输入不得为空");
        }
        if (!notBlank(props.getEmbeddingModel())) {
            throw new IllegalStateException("嵌入模型未配置，请设置环境变量 LLM_EMBEDDING_MODEL");
        }
        // D013：独立端点优先，未配置则复用 chat 端点；embed 不要求 chat 的 model 已配置
        String baseUrl = notBlank(props.getEmbeddingBaseUrl()) ? props.getEmbeddingBaseUrl() : props.getBaseUrl();
        String apiKey = notBlank(props.getEmbeddingApiKey()) ? props.getEmbeddingApiKey() : props.getApiKey();
        if (!notBlank(baseUrl) || !notBlank(apiKey)) {
            throw new LlmException("嵌入端点未配置，请设置 LLM_BASE_URL / LLM_API_KEY"
                    + " 或嵌入专用的 LLM_EMBEDDING_BASE_URL / LLM_EMBEDDING_API_KEY");
        }
        Map<String, Object> requestBody = Map.of("model", props.getEmbeddingModel(), "input", texts);
        try {
            EmbeddingResponse response = restClient.post()
                    .uri(endpointUrl(baseUrl, "/embeddings"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .body(requestBody)
                    .retrieve()
                    .body(EmbeddingResponse.class);
            return alignVectors(texts.size(), response);
        } catch (RestClientResponseException e) {
            throw new LlmException("LLM 端点返回错误状态：" + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            String message = hasIoCause(e) ? "LLM 端点不可达或超时" : "LLM 响应处理失败";
            throw new LlmException(message, e);
        }
    }

    /** 端点返回的 data 数量、index 必须与输入一一对应，否则整体视为失败（spec 不变式） */
    private static List<float[]> alignVectors(int expected, EmbeddingResponse response) {
        if (response == null || response.data() == null || response.data().size() != expected) {
            throw new LlmException("嵌入响应与输入数量不对应");
        }
        float[][] vectors = new float[expected][];
        for (EmbeddingData item : response.data()) {
            if (item == null || item.embedding() == null || item.embedding().isEmpty()
                    || item.index() < 0 || item.index() >= expected || vectors[item.index()] != null) {
                throw new LlmException("嵌入响应的 index 或向量不合法");
            }
            float[] vector = new float[item.embedding().size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = item.embedding().get(i).floatValue();
            }
            vectors[item.index()] = vector;
        }
        return List.of(vectors);
    }

    private static boolean hasIoCause(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private boolean configured() {
        return notBlank(props.getBaseUrl()) && notBlank(props.getApiKey()) && notBlank(props.getModel());
    }

    /** 尾斜杠归一化：base-url 习惯性带 '/' 时避免拼出 //chat/completions（chat 与嵌入端点各自归一化） */
    private String endpointUrl(String baseUrl, String path) {
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + path;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String requireContent(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new LlmException("LLM 返回内容为空");
        }
        Message message = response.choices().get(0).message();
        if (message == null || message.content() == null || message.content().isBlank()) {
            throw new LlmException("LLM 返回内容为空");
        }
        return message.content();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<EmbeddingData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingData(int index, List<Double> embedding) {
    }
}
