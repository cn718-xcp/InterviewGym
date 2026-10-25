package com.interviewgym.core;

/**
 * LLM 输出解析失败（修复重试后仍不合法）。
 * 消息只描述 schema 违规原因，不回显 LLM 输出内容（红线 2）。
 */
public class LlmOutputParseException extends RuntimeException {

    public LlmOutputParseException(String message) {
        super(message);
    }
}
