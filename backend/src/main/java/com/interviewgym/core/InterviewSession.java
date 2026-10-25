package com.interviewgym.core;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一场模拟面试会话。不可变：更新 = 复制新对象整体替换（spec: persistent-store.md）。
 * resumeAssisted（v2，D012）：本场出题是否注入了简历检索片段。
 */
public record InterviewSession(String id, String jdText, SessionStatus status,
                               List<Question> questions, Map<String, Answer> answers,
                               Instant createdAt, boolean resumeAssisted) {

    public InterviewSession {
        questions = questions == null ? List.of() : List.copyOf(questions);
        answers = answers == null ? Map.of() : Map.copyOf(answers);
    }

    /** v1 形状兼容构造：无简历辅助标记（默认 false） */
    public InterviewSession(String id, String jdText, SessionStatus status,
                            List<Question> questions, Map<String, Answer> answers, Instant createdAt) {
        this(id, jdText, status, questions, answers, createdAt, false);
    }

    public InterviewSession withAnswer(Answer answer) {
        Map<String, Answer> next = new HashMap<>(answers);
        next.put(answer.questionId(), answer);
        return new InterviewSession(id, jdText, status, questions, next, createdAt, resumeAssisted);
    }

    public InterviewSession withStatus(SessionStatus newStatus) {
        return new InterviewSession(id, jdText, newStatus, questions, answers, createdAt, resumeAssisted);
    }

    /** 全部题目均已作答（evaluate 的前置条件，spec: evaluation.md） */
    public boolean allAnswered() {
        return answers.keySet().equals(questions.stream().map(Question::id).collect(Collectors.toSet()));
    }
}
