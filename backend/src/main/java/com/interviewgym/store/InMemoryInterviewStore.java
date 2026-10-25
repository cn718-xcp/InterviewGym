package com.interviewgym.store;

import com.interviewgym.core.EvaluationReport;
import com.interviewgym.core.InterviewSession;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

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

    /**
     * 原子读-改-写（ConcurrentHashMap.compute，持锁执行 updater）。
     * 调用方不得再自行 find→改→save 组合——那会把并发提交互相覆盖（AI-FAILURES #6）。
     *
     * @return 更新后的会话；id 不存在或非 UUID 时为空 Optional（调用方转 404）
     * @throws NullPointerException updater 返回 null（视为编程错误，映射不变）
     */
    public Optional<InterviewSession> updateSession(String id, UnaryOperator<InterviewSession> updater) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(updater, "updater");
        UUID key = toUuid(id);
        if (key == null) {
            return Optional.empty();
        }
        InterviewSession updated = sessions.compute(key, (k, current) ->
                current == null ? null : Objects.requireNonNull(updater.apply(current), "updater 结果"));
        return Optional.ofNullable(updated);
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
