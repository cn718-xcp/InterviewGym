package com.interviewgym.rag;

import java.time.Instant;

/**
 * GET/PUT /api/resume 的响应：简历索引状态（含原文，供前端回显）。
 */
public record ResumeStatus(Instant updatedAt, int chunkCount, String embedModel, int embedDim, String sourceText) {
}
