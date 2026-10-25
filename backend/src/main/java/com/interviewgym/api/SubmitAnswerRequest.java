package com.interviewgym.api;

/**
 * 提交某题答案请求。
 */
public record SubmitAnswerRequest(String questionId, String text) {
}
