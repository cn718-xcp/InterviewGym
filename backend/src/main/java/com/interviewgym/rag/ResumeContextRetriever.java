package com.interviewgym.rag;

import com.interviewgym.llm.LlmClient;
import com.interviewgym.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 出题时的简历上下文检索器（spec: resume-rag.md）：JD 向量化 → 与库内块做余弦 top-k →
 * 拼装为「### {节标题}\n{块文本}」的上下文字符串。
 * 出题路径的失败一律降级为 Optional.empty()（退回 v1 无简历出题），不阻断用户。
 */
@Component
public class ResumeContextRetriever {

    /** 检索块数上限（spec 契约值，改动需走 spec 修订） */
    static final int TOP_K = 6;

    private static final Logger log = LoggerFactory.getLogger(ResumeContextRetriever.class);

    private final ResumeRepository repository;
    private final LlmClient llmClient;

    public ResumeContextRetriever(ResumeRepository repository, LlmClient llmClient) {
        this.repository = repository;
        this.llmClient = llmClient;
    }

    public Optional<String> retrieveContext(String jdText) {
        Objects.requireNonNull(jdText, "jdText");
        if (repository.findResume().isEmpty() || repository.chunkCount() == 0) {
            return Optional.empty();
        }
        List<ChunkVector> chunks = repository.findChunkVectors();
        float[] jdVector;
        try {
            jdVector = llmClient.embed(List.of(jdText)).get(0);
        } catch (IllegalStateException | LlmException e) {
            log.warn("简历检索不可用，本次出题退回无简历模式：{}", e.getMessage());
            return Optional.empty();
        }
        if (chunks.stream().anyMatch(c -> c.embedding().length != jdVector.length)) {
            // 嵌入模型变更后未重新上传简历 → 索引作废，宁可不出注入也不用错维度的相似度
            log.warn("简历索引维度与当前嵌入模型不一致，请重新上传简历重建索引");
            return Optional.empty();
        }
        String context = chunks.stream()
                .sorted((a, b) -> Double.compare(cosine(jdVector, b.embedding()), cosine(jdVector, a.embedding())))
                .limit(TOP_K)
                .map(c -> "### " + (c.section().isBlank() ? "简历片段" : c.section()) + "\n" + c.text())
                .collect(Collectors.joining("\n\n"));
        return Optional.of(context);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
