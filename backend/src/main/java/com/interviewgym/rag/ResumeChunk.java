package com.interviewgym.rag;

/**
 * 简历切块结果（spec: resume-rag.md）：chunkIndex 全局连续，section 为切块时所在节标题（可为空）。
 */
public record ResumeChunk(int chunkIndex, String section, String text) {
}
