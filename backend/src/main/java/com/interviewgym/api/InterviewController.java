package com.interviewgym.api;

import com.interviewgym.core.Answer;
import com.interviewgym.core.EvaluationReport;
import com.interviewgym.core.EvaluationService;
import com.interviewgym.core.InterviewSession;
import com.interviewgym.core.Question;
import com.interviewgym.core.QuestionGenerationService;
import com.interviewgym.core.SessionStatus;
import com.interviewgym.core.SessionSummary;
import com.interviewgym.store.InterviewStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模拟面试 REST 端点（spec: docs/specs/question-generation.md / evaluation.md）。
 * 本层不做业务判断，只做参数校验与编排；不记录任何用户内容日志（红线 2）。
 */
@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    /** JD 有效长度下限：太短的 JD 无法支撑出题（spec: question-generation.md 输入边界） */
    static final int MIN_JD_LENGTH = 20;

    private final QuestionGenerationService questionGenerationService;
    private final EvaluationService evaluationService;
    private final InterviewStore store;

    public InterviewController(QuestionGenerationService questionGenerationService,
                               EvaluationService evaluationService,
                               InterviewStore store) {
        this.questionGenerationService = questionGenerationService;
        this.evaluationService = evaluationService;
        this.store = store;
    }

    /** POST /api/interviews {jdText} → 会话 + 10 题（1 次 LLM 调用） */
    @PostMapping
    public InterviewSession create(@RequestBody CreateInterviewRequest request) {
        String jdText = request.jdText() == null ? "" : request.jdText().trim();
        if (jdText.length() < MIN_JD_LENGTH) {
            throw new IllegalArgumentException("JD 不能为空且至少 " + MIN_JD_LENGTH + " 字");
        }
        List<Question> questions = questionGenerationService.generate(jdText);
        InterviewSession session = new InterviewSession(UUID.randomUUID().toString(), jdText,
                SessionStatus.ACTIVE, questions, Map.of(), Instant.now());
        store.saveSession(session);
        return session;
    }

    /** GET /api/interviews → 会话摘要列表（跨天历史，创建序倒序） */
    @GetMapping
    public List<SessionSummary> list() {
        return store.listSessions();
    }

    /** GET /api/interviews/{id} → 会话详情 */
    @GetMapping("/{id}")
    public InterviewSession get(@PathVariable String id) {
        return requireSession(id);
    }

    /** POST /api/interviews/{id}/answers {questionId, text} → 存答案，返回最新会话 */
    @PostMapping("/{id}/answers")
    public InterviewSession answer(@PathVariable String id, @RequestBody SubmitAnswerRequest request) {
        // 原子读-改-写：并发提交不互相覆盖（AI-FAILURES #6）
        return store.updateSession(id, session -> {
                    if (session.status() != SessionStatus.ACTIVE) {
                        throw new IllegalStateException("会话已评估，不能再作答");
                    }
                    String questionId = request.questionId();
                    if (questionId == null || session.questions().stream().noneMatch(q -> q.id().equals(questionId))) {
                        throw new IllegalArgumentException("questionId 不属于本场面试");
                    }
                    String text = request.text() == null ? "" : request.text().trim();
                    if (text.isEmpty()) {
                        throw new IllegalArgumentException("答案不能为空");
                    }
                    return session.withAnswer(new Answer(questionId, text, Instant.now()));
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    /** POST /api/interviews/{id}/evaluate → 评估报告（1 次 LLM 调用） */
    @PostMapping("/{id}/evaluate")
    public EvaluationReport evaluate(@PathVariable String id) {
        return evaluationService.evaluate(requireSession(id));
    }

    /** GET /api/interviews/{id}/report → 查看报告 */
    @GetMapping("/{id}/report")
    public EvaluationReport report(@PathVariable String id) {
        requireSession(id);
        return store.findReport(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在，先完成评估"));
    }

    private InterviewSession requireSession(String id) {
        return store.findSession(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }
}
