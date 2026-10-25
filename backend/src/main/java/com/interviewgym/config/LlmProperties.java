package com.interviewgym.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 端点配置（D005：OpenAI 兼容端点，可配置）。
 * api-key 只允许经环境变量注入（AGENTS.md 红线 1），任何代码与配置文件不得出现明文密钥。
 */
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** OpenAI 兼容端点，如 https://open.bigmodel.cn/api/paas/v4 */
    private String baseUrl = "";

    /** 从环境变量 LLM_API_KEY 注入，禁止写死 */
    private String apiKey = "";

    /** 模型名，如 glm-4-air */
    private String model = "";

    /** 嵌入模型名（v2，D011），如 embedding-3；空 = 简历 RAG 关闭。从环境变量 LLM_EMBEDDING_MODEL 注入 */
    private String embeddingModel = "";

    /** 嵌入端点独立 base-url（D013），如 https://open.bigmodel.cn/api/paas/v4；空 = 复用 base-url。环境变量 LLM_EMBEDDING_BASE_URL */
    private String embeddingBaseUrl = "";

    /** 嵌入端点独立 api-key（D013）；空 = 复用 api-key。环境变量 LLM_EMBEDDING_API_KEY */
    private String embeddingApiKey = "";

    /** 语音转写模型名（v3，D014），如 FunAudioLLM/SenseVoiceSmall；空 = 语音功能关闭。环境变量 LLM_ASR_MODEL */
    private String asrModel = "";

    /** ASR 端点独立 base-url（D014），如 https://api.siliconflow.cn/v1；空 = 复用 base-url。环境变量 LLM_ASR_BASE_URL */
    private String asrBaseUrl = "";

    /** ASR 端点独立 api-key（D014）；空 = 复用 api-key。环境变量 LLM_ASR_API_KEY */
    private String asrApiKey = "";

    /** 单次调用读超时（秒）。出题/评估单次生成较慢，默认给足（spec: llm-client.md） */
    private int readTimeoutSeconds = 90;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    public void setEmbeddingApiKey(String embeddingApiKey) {
        this.embeddingApiKey = embeddingApiKey;
    }

    public String getAsrModel() {
        return asrModel;
    }

    public void setAsrModel(String asrModel) {
        this.asrModel = asrModel;
    }

    public String getAsrBaseUrl() {
        return asrBaseUrl;
    }

    public void setAsrBaseUrl(String asrBaseUrl) {
        this.asrBaseUrl = asrBaseUrl;
    }

    public String getAsrApiKey() {
        return asrApiKey;
    }

    public void setAsrApiKey(String asrApiKey) {
        this.asrApiKey = asrApiKey;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }
}
