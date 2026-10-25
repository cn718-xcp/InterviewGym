package com.interviewgym.core;

/**
 * 逐题评分（锚点 1-5 见 docs/specs/evaluation.md，红线 3 保护）。
 */
public record QuestionScore(String questionId, int score, String strength, String improvement) {
}
