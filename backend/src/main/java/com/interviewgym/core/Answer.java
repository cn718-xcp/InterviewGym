package com.interviewgym.core;

import java.time.Instant;

/**
 * 候选人对一道题的作答。
 */
public record Answer(String questionId, String text, Instant answeredAt) {
}
