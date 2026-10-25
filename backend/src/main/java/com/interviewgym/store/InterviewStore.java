package com.interviewgym.store;

import com.interviewgym.core.EvaluationReport;
import com.interviewgym.core.InterviewSession;
import com.interviewgym.core.SessionSummary;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 会话与报告存储契约（spec: persistent-store.md，D010）。
 * 签名存储无关：core/api 只依赖本接口，不感知 SQL/JSON 细节。
 */
public interface InterviewStore {

    void saveSession(InterviewSession session);

    Optional<InterviewSession> findSession(String id);

    /**
     * 原子读-改-写：实现方必须保证并发下不丢更新（乐观锁 + 重试，spec 不变式）。
     * 调用方不得再自行 find→改→save 组合——那会把并发提交互相覆盖（AI-FAILURES #6）。
     *
     * @return 更新后的会话；id 不存在或非 UUID 时为空 Optional（调用方转 404）
     * @throws IllegalStateException updater 抛出的状态冲突（映射 409），或乐观锁重试耗尽
     */
    Optional<InterviewSession> updateSession(String id, UnaryOperator<InterviewSession> updater);

    void saveReport(EvaluationReport report);

    Optional<EvaluationReport> findReport(String sessionId);

    /** 会话摘要列表，创建序倒序（跨天历史的读模型） */
    List<SessionSummary> listSessions();
}
