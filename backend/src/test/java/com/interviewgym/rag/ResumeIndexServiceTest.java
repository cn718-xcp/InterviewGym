package com.interviewgym.rag;

import com.interviewgym.config.LlmProperties;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResumeIndexServiceTest {

    private static final String RESUME_A = "# 技能\n\n" + "甲".repeat(150) + "\n\n# 项目\n\n" + "乙".repeat(150);
    private static final String RESUME_B = "# 技能\n\n" + "丙".repeat(300);

    @TempDir
    Path tempDir;

    private LlmClient llmClient;
    private LlmProperties props;
    private ResumeRepository repository;
    private ResumeIndexService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        props = new LlmProperties();
        props.setEmbeddingModel("embedding-3");
        repository = new ResumeRepository(
                new org.springframework.jdbc.core.JdbcTemplate(SqliteTestSupport.newDataSource(tempDir.resolve("resume.db"))));
        service = new ResumeIndexService(new ResumeChunker(), repository, llmClient, props);
    }

    private void stubEmbedding() {
        when(llmClient.embed(anyList())).thenAnswer(inv -> {
            List<String> input = inv.getArgument(0);
            return input.stream().map(t -> new float[]{0.1f, 0.2f, 0.3f}).toList();
        });
    }

    @Test
    void saveIndexesAndStoresStatus() {
        stubEmbedding();

        ResumeStatus status = service.save(RESUME_A);

        assertThat(status.chunkCount()).isPositive();
        assertThat(status.embedModel()).isEqualTo("embedding-3");
        assertThat(status.embedDim()).isEqualTo(3);
        assertThat(status.sourceText()).isEqualTo(RESUME_A);
        assertThat(service.status()).contains(status);
    }

    @Test
    void reuploadReplacesPreviousIndex() {
        stubEmbedding();
        service.save(RESUME_A);

        ResumeStatus second = service.save(RESUME_B);

        assertThat(second.sourceText()).isEqualTo(RESUME_B);
        // RESUME_B 只切出 1 块；若旧索引残留则会 >1（spec：新上传整体替换）
        assertThat(second.chunkCount()).isEqualTo(1);
        assertThat(repository.chunkCount()).isEqualTo(1);
    }

    @Test
    void shortResumeRejectedWithoutEmbedding() {
        assertThatThrownBy(() -> service.save("太短"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("太短");
        verifyNoInteractions(llmClient);
    }

    @Test
    void missingEmbeddingModelRejectedWithoutEmbedding() {
        props.setEmbeddingModel("");

        assertThatThrownBy(() -> service.save(RESUME_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LLM_EMBEDDING_MODEL");
        verifyNoInteractions(llmClient);
    }

    @Test
    void embeddingFailurePropagatesAndStoresNothing() {
        when(llmClient.embed(anyList())).thenThrow(new LlmException("LLM 端点不可达或超时"));

        assertThatThrownBy(() -> service.save(RESUME_A)).isInstanceOf(LlmException.class);
        assertThat(service.status()).isEqualTo(Optional.empty());
    }

    @Test
    void deleteClearsResumeAndIndex() {
        stubEmbedding();
        service.save(RESUME_A);

        service.delete();

        assertThat(service.status()).isEmpty();
    }
}
