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

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }
}
