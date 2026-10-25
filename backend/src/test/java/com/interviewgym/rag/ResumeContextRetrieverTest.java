package com.interviewgym.rag;

import com.interviewgym.llm.LlmClient;
import com.interviewgym.llm.LlmException;
import com.interviewgym.store.SqliteTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResumeContextRetrieverTest {

    @TempDir
    Path tempDir;

    private LlmClient llmClient;
    private ResumeRepository repository;
    private ResumeContextRetriever retriever;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        repository = new ResumeRepository(
                new org.springframework.jdbc.core.JdbcTemplate(SqliteTestSupport.newDataSource(tempDir.resolve("rag.db"))));
        retriever = new ResumeContextRetriever(repository, llmClient);
    }

    private void indexedChunks(ChunkVector... chunks) {
        List<ResumeChunk> plain = new java.util.ArrayList<>();
        List<float[]> vectors = new java.util.ArrayList<>();
        for (int i = 0; i < chunks.length; i++) {
            plain.add(new ResumeChunk(i, chunks[i].section(), chunks[i].text()));
            vectors.add(chunks[i].embedding());
        }
        repository.replaceAll("源简历", "embedding-3", chunks[0].embedding().length, plain, vectors);
    }

    private void jdVector(float... vector) {
        when(llmClient.embed(anyList())).thenReturn(List.of(vector));
    }

    @Test
    void retrievesTopChunksSortedByCosineWithSectionHeaders() {
        indexedChunks(
                new ChunkVector(0, "技能", "技能块", new float[]{1f, 0f}),
                new ChunkVector(1, "项目", "项目块", new float[]{0.70710677f, 0.70710677f}),
                new ChunkVector(2, "教育", "教育块", new float[]{0f, 1f}));
        jdVector(1f, 0f);

        Optional<String> context = retriever.retrieveContext("目标岗位 JD");

        assertThat(context).isPresent();
        assertThat(context.orElseThrow()).isEqualTo("### 技能\n技能块\n\n### 项目\n项目块\n\n### 教育\n教育块");
    }

    @Test
    void limitsResultToTopK() {
        ChunkVector[] many = new ChunkVector[9];
        for (int i = 0; i < many.length; i++) {
            many[i] = new ChunkVector(i, "节" + i, "块" + i, new float[]{1f, 0f});
        }
        indexedChunks(many);
        jdVector(1f, 0f);

        Optional<String> context = retriever.retrieveContext("JD");

        assertThat(context).isPresent();
        assertThat(context.orElseThrow().split("\n\n")).hasSize(ResumeContextRetriever.TOP_K);
    }

    @Test
    void emptyWhenNoResumeIndexed() {
        assertThat(retriever.retrieveContext("JD")).isEmpty();
    }

    @Test
    void degradesToEmptyWhenEmbeddingFails() {
        indexedChunks(new ChunkVector(0, "技能", "技能块", new float[]{1f, 0f}));
        when(llmClient.embed(anyList())).thenThrow(new LlmException("LLM 端点不可达或超时"));

        assertThat(retriever.retrieveContext("JD")).isEmpty();
    }

    @Test
    void degradesToEmptyWhenDimensionMismatch() {
        // 库内 3 维、当前模型产出 2 维 → 索引作废，宁可不出注入
        indexedChunks(new ChunkVector(0, "技能", "技能块", new float[]{1f, 0f, 0f}));
        jdVector(1f, 0f);

        assertThat(retriever.retrieveContext("JD")).isEmpty();
    }
}
