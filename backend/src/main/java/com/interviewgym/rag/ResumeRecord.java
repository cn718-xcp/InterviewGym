package com.interviewgym.rag;

import java.time.Instant;

/**
 * 当前活跃简历的存储行（单行表，spec: resume-rag.md）。
 */
public record ResumeRecord(Instant updatedAt, String sourceText, String embedModel, int embedDim) {
}
