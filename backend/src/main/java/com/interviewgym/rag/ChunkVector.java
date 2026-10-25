package com.interviewgym.rag;

/**
 * 带向量的简历块（检索读模型）。
 */
public record ChunkVector(int chunkIndex, String section, String text, float[] embedding) {
}
