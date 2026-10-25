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

/**
 * OpenAI 兼容 chat/completions 的最小客户端（D005 可配置端点、D007 自写 RestClient，spec: docs/specs/llm-client.md）。
 * 只负责一次可靠调用并返回文本内容；JSON 解析与修复重试属 core/ 业务层。
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
        log.info("LLM 客户端就绪：base-url=[{}], model=[{}], api-key={}, 读超时={}s",
                props.getBaseUrl(), props.getModel(),
                notBlank(props.getApiKey()) ? "已配置" : "未配置", props.getReadTimeoutSeconds());
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
                    .uri(props.getBaseUrl() + "/chat/completions")
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
}
