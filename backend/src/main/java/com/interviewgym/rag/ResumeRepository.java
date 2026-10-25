package com.interviewgym.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 简历与其向量索引的 SQLite 存储（表见 schema.sql，spec: resume-rag.md）。
 * 单活跃简历：replaceAll 全量重建（清旧写新），不做增量。
 */
@Component
public class ResumeRepository {

    private final JdbcTemplate jdbc;

    public ResumeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全量重建简历索引；chunks 与 vectors 必须按 chunkIndex 一一对应 */
    @Transactional
    public void replaceAll(String sourceText, String embedModel, int embedDim,
                           List<ResumeChunk> chunks, List<float[]> vectors) {
        Objects.requireNonNull(sourceText, "sourceText");
        Objects.requireNonNull(embedModel, "embedModel");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(vectors, "vectors");
        if (chunks.size() != vectors.size() || chunks.isEmpty()) {
            throw new IllegalArgumentException("块与向量必须非空且数量一致");
        }
        jdbc.update("DELETE FROM resume_chunks");
        jdbc.update("DELETE FROM resume");
        jdbc.update("INSERT INTO resume(id, updated_at, source_text, embed_model, embed_dim) VALUES (1, ?, ?, ?, ?)",
                Instant.now().toString(), sourceText, embedModel, embedDim);
        jdbc.batchUpdate("INSERT INTO resume_chunks(chunk_index, section, text, embedding) VALUES (?, ?, ?, ?)",
                chunks, 100, (ps, chunk) -> {
                    ps.setInt(1, chunk.chunkIndex());
                    ps.setString(2, chunk.section());
                    ps.setString(3, chunk.text());
                    ps.setBytes(4, VectorCodec.toBytes(vectors.get(chunk.chunkIndex())));
                });
    }

    public Optional<ResumeRecord> findResume() {
        return jdbc.query("SELECT updated_at, source_text, embed_model, embed_dim FROM resume",
                        (rs, i) -> new ResumeRecord(Instant.parse(rs.getString("updated_at")),
                                rs.getString("source_text"), rs.getString("embed_model"), rs.getInt("embed_dim")))
                .stream().findFirst();
    }

    public int chunkCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM resume_chunks", Integer.class);
        return count == null ? 0 : count;
    }

    public List<ChunkVector> findChunkVectors() {
        return jdbc.query("SELECT chunk_index, section, text, embedding FROM resume_chunks ORDER BY chunk_index",
                (rs, i) -> new ChunkVector(rs.getInt("chunk_index"), rs.getString("section"),
                        rs.getString("text"), VectorCodec.toFloats(rs.getBytes("embedding"))));
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM resume_chunks");
        jdbc.update("DELETE FROM resume");
    }
}
