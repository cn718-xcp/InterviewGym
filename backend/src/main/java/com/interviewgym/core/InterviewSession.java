package com.interviewgym.core;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一场模拟面试会话。不可变：更新 = 复制新对象整体替换（spec: in-memory-store.md）。
 */
public record InterviewSession(String id, String jdText, SessionStatus status,
                               List<Question> questions, Map<String, Answer> answers,
                               Instant createdAt) {

    public InterviewSession {
        questions = questions == null ? List.of() : List.copyOf(questions);
        answers = answers == null ? Map.of() : Map.copyOf(answers);
    }

    public InterviewSession withAnswer(Answer answer) {
        Map<String, Answer> next = new HashMap<>(answers);
        next.put(answer.questionId(), answer);
        return new InterviewSession(id, jdText, status, questions, next, createdAt);
    }

    public InterviewSession withStatus(SessionStatus newStatus) {
        return new InterviewSession(id, jdText, newStatus, questions, answers, createdAt);
    }

    /** 全部题目均已作答（evaluate 的前置条件，spec: evaluation.md） */
    public boolean allAnswered() {
        return answers.keySet().equals(questions.stream().map(Question::id).collect(Collectors.toSet()));
    }
}
