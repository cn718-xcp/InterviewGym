package com.interviewgym.core;

import java.util.List;

/**
 * 评估报告。averageScore 由代码计算（不信 LLM 算术，spec: evaluation.md）。
 */
public record EvaluationReport(String sessionId, List<QuestionScore> perQuestion,
                               double averageScore, String summary,
                               List<WeakPoint> weakPoints, List<String> reviewAdvice) {

    public EvaluationReport {
        perQuestion = perQuestion == null ? List.of() : List.copyOf(perQuestion);
        weakPoints = weakPoints == null ? List.of() : List.copyOf(weakPoints);
        reviewAdvice = reviewAdvice == null ? List.of() : List.copyOf(reviewAdvice);
    }
}
