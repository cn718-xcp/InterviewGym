package com.interviewgym.rag;

import com.interviewgym.config.LlmProperties;
import com.interviewgym.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 简历索引服务（spec: resume-rag.md）：上传 → 切块 → 一次嵌入调用 → 全量落库。
 * 上传路径不静默吞错：嵌入未配置 → 400 语义，端点失败 → 502 语义（向上传播）。
 */
@Component
public class ResumeIndexService {

    /** 简历有效长度下限（API 层契约在 spec: resume-rag.md 输入边界） */
    static final int MIN_RESUME_LENGTH = 100;

    private static final Logger log = LoggerFactory.getLogger(ResumeIndexService.class);

    private final ResumeChunker chunker;
    private final ResumeRepository repository;
    private final LlmClient llmClient;
    private final LlmProperties llmProperties;

    public ResumeIndexService(ResumeChunker chunker, ResumeRepository repository,
                              LlmClient llmClient, LlmProperties llmProperties) {
        this.chunker = chunker;
        this.repository = repository;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
    }

    public ResumeStatus save(String resumeText) {
        String text = resumeText == null ? "" : resumeText.trim();
        if (text.length() < MIN_RESUME_LENGTH) {
            throw new IllegalArgumentException("简历内容太短，至少 " + MIN_RESUME_LENGTH + " 字");
        }
        String embeddingModel = llmProperties.getEmbeddingModel();
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("上传简历需先配置嵌入模型环境变量 LLM_EMBEDDING_MODEL");
        }
        List<ResumeChunk> chunks = chunker.chunk(text);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("简历内容无法切块，请检查格式");
        }
        // 嵌入输入带节标题：标题信号进向量，检索更准
        List<float[]> vectors = llmClient.embed(chunks.stream()
                .map(c -> (c.section().isBlank() ? "" : c.section() + "\n") + c.text())
                .toList());
        repository.replaceAll(text, embeddingModel.trim(), vectors.get(0).length, chunks, vectors);
        // 只记数量级信息，不记简历内容（红线 2）
        log.info("简历索引已重建：块数={}", chunks.size());
        return status().orElseThrow();
    }

    public Optional<ResumeStatus> status() {
        return repository.findResume()
                .map(r -> new ResumeStatus(r.updatedAt(), repository.chunkCount(),
                        r.embedModel(), r.embedDim(), r.sourceText()));
    }

    public void delete() {
        repository.deleteAll();
        log.info("简历及其索引已删除");
    }
}
