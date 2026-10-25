package com.interviewgym.store;

import com.interviewgym.core.EvaluationReport;
import com.interviewgym.core.InterviewSession;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1 内存会话存储（D004：不引入数据库；spec: in-memory-store.md）。
 * 用户内容只存在内存、永不落盘（红线 2）；接口签名存储无关，v2 换持久化时 core/api 零改动。
 */
@Component
public class InMemoryInterviewStore {

    private final Map<UUID, InterviewSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, EvaluationReport> reports = new ConcurrentHashMap<>();

    public void saveSession(InterviewSession session) {
        Objects.requireNonNull(session, "session");
        sessions.put(UUID.fromString(session.id()), session);
    }

    public Optional<InterviewSession> findSession(String id) {
        Objects.requireNonNull(id, "id");
        UUID key = toUuid(id);
        return key == null ? Optional.empty() : Optional.ofNullable(sessions.get(key));
    }

    public void saveReport(EvaluationReport report) {
        Objects.requireNonNull(report, "report");
        reports.put(UUID.fromString(report.sessionId()), report);
    }

    public Optional<EvaluationReport> findReport(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        UUID key = toUuid(sessionId);
        return key == null ? Optional.empty() : Optional.ofNullable(reports.get(key));
    }

    private static UUID toUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            // 非 UUID 格式视同不存在，由上层转 404
            return null;
        }
    }
}
