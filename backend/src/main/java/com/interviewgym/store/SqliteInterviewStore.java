package com.interviewgym.store;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewgym.core.EvaluationReport;
import com.interviewgym.core.InterviewSession;
import com.interviewgym.core.SessionStatus;
import com.interviewgym.core.SessionSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * v2 SQLite 会话存储（D010，spec: persistent-store.md）。
 * 域对象以 JSON 文档落 payload 列；updateSession 用 version 乐观锁 + 有界重试保
 * v1 的原子读-改-写语义（并发答案都落地、双评估必 409，AI-FAILURES #6 不回归）。
 */
@Component
public class SqliteInterviewStore implements InterviewStore {

    /** 乐观锁重试上限：超出视为持续并发冲突，抛 409 语义异常 */
    private static final int MAX_UPDATE_ATTEMPTS = 5;

    /** 历史列表 JD 摘要截断长度 */
    static final int JD_EXCERPT_MAX = 60;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SqliteInterviewStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveSession(InterviewSession session) {
        Objects.requireNonNull(session, "session");
        jdbc.update("""
                        INSERT INTO sessions(id, status, created_at, resume_assisted, version, payload)
                        VALUES (?, ?, ?, ?, 0, ?)
                        ON CONFLICT(id) DO UPDATE SET
                            status = excluded.status,
                            created_at = excluded.created_at,
                            resume_assisted = excluded.resume_assisted,
                            payload = excluded.payload,
                            version = sessions.version + 1
                        """,
                session.id(), session.status().name(), session.createdAt().toString(),
                session.resumeAssisted() ? 1 : 0, writeJson(session));
    }

    @Override
    public Optional<InterviewSession> findSession(String id) {
        Objects.requireNonNull(id, "id");
        if (toUuid(id) == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT payload FROM sessions WHERE id=?", (rs, i) -> rs.getString("payload"), id)
                .stream().findFirst()
                .map(payload -> readJson(payload, InterviewSession.class));
    }

    @Override
    public Optional<InterviewSession> updateSession(String id, UnaryOperator<InterviewSession> updater) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(updater, "updater");
        if (toUuid(id) == null) {
            return Optional.empty();
        }
        for (int attempt = 0; attempt < MAX_UPDATE_ATTEMPTS; attempt++) {
            List<SessionRow> rows = jdbc.query("SELECT version, payload FROM sessions WHERE id=?",
                    (rs, i) -> new SessionRow(rs.getLong("version"), rs.getString("payload")), id);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            SessionRow row = rows.get(0);
            InterviewSession current = readJson(row.payload(), InterviewSession.class);
            InterviewSession updated = Objects.requireNonNull(updater.apply(current), "updater 结果");
            int changed = jdbc.update("""
                            UPDATE sessions
                            SET status = ?, resume_assisted = ?, payload = ?, version = version + 1
                            WHERE id = ? AND version = ?
                            """,
                    updated.status().name(), updated.resumeAssisted() ? 1 : 0, writeJson(updated),
                    id, row.version());
            if (changed == 1) {
                return Optional.of(updated);
            }
            // version 被并发写顶掉：重读最新状态再应用 updater（并发提交的更新都落地，v1 语义）
        }
        throw new IllegalStateException("并发冲突：会话正被其他操作修改，请稍后重试");
    }

    @Override
    public void saveReport(EvaluationReport report) {
        Objects.requireNonNull(report, "report");
        jdbc.update("INSERT OR REPLACE INTO reports(session_id, payload) VALUES (?, ?)",
                report.sessionId(), writeJson(report));
    }

    @Override
    public Optional<EvaluationReport> findReport(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (toUuid(sessionId) == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT payload FROM reports WHERE session_id=?", (rs, i) -> rs.getString("payload"), sessionId)
                .stream().findFirst()
                .map(payload -> readJson(payload, EvaluationReport.class));
    }

    @Override
    public List<SessionSummary> listSessions() {
        return jdbc.query("""
                        SELECT s.id, s.status, s.created_at, s.resume_assisted,
                               json_extract(s.payload, '$.jdText') AS jd_text,
                               json_extract(r.payload, '$.averageScore') AS average_score
                        FROM sessions s
                        LEFT JOIN reports r ON r.session_id = s.id
                        ORDER BY s.rowid DESC
                        """,
                (rs, i) -> new SessionSummary(
                        rs.getString("id"),
                        SessionStatus.valueOf(rs.getString("status")),
                        Instant.parse(rs.getString("created_at")),
                        excerpt(rs.getString("jd_text")),
                        rs.getInt("resume_assisted") == 1,
                        (Double) rs.getObject("average_score")));
    }

    private static String excerpt(String jdText) {
        if (jdText == null) {
            return "";
        }
        return jdText.length() <= JD_EXCERPT_MAX ? jdText : jdText.substring(0, JD_EXCERPT_MAX) + "…";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("存储序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("存储反序列化失败", e);
        }
    }

    private static UUID toUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            // 非 UUID 格式视同不存在，由上层转 404
            return null;
        }
    }

    private record SessionRow(long version, String payload) {
    }
}
