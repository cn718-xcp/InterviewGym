package com.interviewgym.core;

import java.time.Instant;

/**
 * 历史列表用的会话摘要读模型：jdText 截断，不含题目与答案。
 * averageScore 为 null 表示该会话尚未评估。
 */
public record SessionSummary(String id, SessionStatus status, Instant createdAt,
                             String jdExcerpt, boolean resumeAssisted, Double averageScore) {
}
