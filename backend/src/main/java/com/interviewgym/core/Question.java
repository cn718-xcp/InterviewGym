package com.interviewgym.core;

/**
 * 一道模拟面试题（schema 见 docs/specs/in-memory-store.md，人类已确认）。
 *
 * @param difficulty BASIC | MEDIUM | HARD
 */
public record Question(String id, int index, QuestionType type, String difficulty,
                       String knowledgePoint, String content) {
}
