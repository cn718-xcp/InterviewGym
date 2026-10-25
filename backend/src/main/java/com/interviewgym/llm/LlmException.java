package com.interviewgym.llm;

/**
 * LLM 调用失败（端点未配置 / 不可达 / 超时 / 返回错误状态 / 返回内容为空）。
 * 消息中不得携带密钥与请求体内容（AGENTS.md 红线 1、2）。
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
