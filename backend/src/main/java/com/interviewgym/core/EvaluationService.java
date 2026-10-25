package com.interviewgym.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewgym.llm.LlmClient;
import com.interviewgym.store.InMemoryInterviewStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评估引擎：全部问答 → 评估报告（spec: docs/specs/evaluation.md）。
 * 评分锚点与聚合规则为红线 3 保护对象（D009，2026-10-25 人类确认），改动需重新走确认流程。
 * 聚合规则在代码不在 LLM：均值代码算；得分 ≤2 的知识点强制入薄弱点。
 */
@Component
public class EvaluationService {

    /** 红线 3 定稿模板 */
    private static final String SYSTEM_PROMPT = "你是一名严格但公正的技术面试评估官。";

    private static final String USER_PROMPT_TEMPLATE = """
            以下是候选人目标岗位的 JD：
            %s

            以下是候选人的逐题作答（questionId 需原样带回）：
            %s

            评分锚点（每题 1-5 分整数）：
            5 = 理解透彻，有深度或实战洞察
            4 = 正确且完整
            3 = 基本正确，但有明显遗漏
            2 = 部分正确，存在错误
            1 = 基本错误或未答

            输出规则：
            1. 逐题评分：score 按锚点；strength 一句亮点；improvement 一句不足（指出错误处的正确答案要点）
            2. summary：总评一段
            3. weakPoints：薄弱知识点列表，knowledgePoint 使用题目给出的原标签，优先得分低的
            4. reviewAdvice：按优先级 2-3 条复习建议
            5. 只输出严格 JSON，无任何其他文字或 markdown 围栏：
            {"perQuestion":[{"questionId":"...","score":1,"strength":"...","improvement":"..."}],"summary":"...","weakPoints":[{"knowledgePoint":"...","reason":"..."}],"reviewAdvice":["...","..."]}
            """;

    private static final String REPAIR_SUFFIX_TEMPLATE =
            "\n\n你上一次的输出不是合法 JSON 或不符合 schema（原因：%s）。请严格按 schema 只输出 JSON。";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final InMemoryInterviewStore store;

    public EvaluationService(LlmClient llmClient, ObjectMapper objectMapper, InMemoryInterviewStore store) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.store = store;
    }

    /**
     * 评估一场已答完的会话：成功后会话置 EVALUATED 并持久化报告（spec 步骤 ⑥）。
     *
     * @throws IllegalStateException 会话已评估 / 尚未答完（API 层转 409）
     * @throws LlmOutputParseException 修复重试 1 次后仍不合法（API 层转 502）
     * @throws com.interviewgym.llm.LlmException LLM 端点失败（API 层转 502；会话保持 ACTIVE 可再评估）
     */
    public EvaluationReport evaluate(InterviewSession session) {
        if (session.status() == SessionStatus.EVALUATED) {
            throw new IllegalStateException("会话已评估，不得重复评估");
        }
        if (!session.allAnswered()) {
            throw new IllegalStateException("还有题目未作答，答完所有题目后才能评估");
        }
        String userPrompt = buildUserPrompt(session);
        String raw = llmClient.chat(SYSTEM_PROMPT, userPrompt);
        ReportDto dto;
        try {
            dto = parseAndValidate(raw, session);
        } catch (IllegalArgumentException firstFailure) {
            String repairPrompt = userPrompt + REPAIR_SUFFIX_TEMPLATE.formatted(firstFailure.getMessage());
            String repaired = llmClient.chat(SYSTEM_PROMPT, repairPrompt);
            try {
                dto = parseAndValidate(repaired, session);
            } catch (IllegalArgumentException secondFailure) {
                throw new LlmOutputParseException("评估结果解析失败，重试后仍不合法：" + secondFailure.getMessage());
            }
        }
        EvaluationReport report = aggregate(session, dto);
        // 原子声明终态：基于当前存储的会话改状态，而非评估开始时的快照——
        // 防并发双评估双写报告，也防 LLM 调用期间新提交的答案被过期快照抹掉（AI-FAILURES #6）
        store.updateSession(session.id(), current -> {
                    if (current.status() == SessionStatus.EVALUATED) {
                        throw new IllegalStateException("会话已评估，不得重复评估");
                    }
                    return current.withStatus(SessionStatus.EVALUATED);
                })
                .orElseThrow(() -> new RuntimeException("会话在评估期间消失，内部状态异常"));
        store.saveReport(report);
        return report;
    }

    private String buildUserPrompt(InterviewSession session) {
        String answersBlock = session.questions().stream()
                .map(q -> {
                    Answer answer = session.answers().get(q.id());
                    return "第 " + q.index() + " 题 [type=" + q.type() + "]（questionId: " + q.id()
                            + "，知识点: " + q.knowledgePoint() + "）\n"
                            + "题目：" + q.content() + "\n"
                            + "候选人答案：" + answer.text();
                })
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
        return USER_PROMPT_TEMPLATE.formatted(session.jdText(), answersBlock);
    }

    private ReportDto parseAndValidate(String raw, InterviewSession session) {
        ReportDto dto;
        try {
            dto = objectMapper.readValue(LlmJsonExtractor.extractJsonObject(raw), ReportDto.class);
        } catch (IllegalArgumentException | JacksonException e) {
            throw new IllegalArgumentException("输出不是合法 JSON 或字段类型错误");
        }
        validate(dto, session);
        return dto;
    }

    private void validate(ReportDto dto, InterviewSession session) {
        int expected = session.questions().size();
        List<ScoreDto> perQuestion = dto.perQuestion();
        if (perQuestion == null || perQuestion.size() != expected) {
            throw new IllegalArgumentException("逐题评分数量应为 " + expected + "，实际为 "
                    + (perQuestion == null ? 0 : perQuestion.size()));
        }
        Set<String> sessionQuestionIds = session.questions().stream()
                .map(Question::id)
                .collect(Collectors.toSet());
        Set<String> seenQuestionIds = new HashSet<>();
        for (ScoreDto score : perQuestion) {
            // questionId 必须与会话题目一一对应：未知、重复、缺失都算不匹配（spec 不变式）
            if (score.questionId() == null || !sessionQuestionIds.contains(score.questionId())
                    || !seenQuestionIds.add(score.questionId())) {
                throw new IllegalArgumentException("questionId 与会话题目不匹配");
            }
            if (score.score() < 1 || score.score() > 5) {
                throw new IllegalArgumentException("score 应为 1-5 的整数，收到 " + score.score());
            }
            if (isBlank(score.strength()) || isBlank(score.improvement())) {
                throw new IllegalArgumentException("strength / improvement 不得为空");
            }
        }
        if (isBlank(dto.summary())) {
            throw new IllegalArgumentException("summary 不得为空");
        }
        if (dto.reviewAdvice() == null || dto.reviewAdvice().isEmpty()
                || dto.reviewAdvice().stream().anyMatch(EvaluationService::isBlank)) {
            throw new IllegalArgumentException("reviewAdvice 需为 2-3 条非空建议");
        }
        if (dto.weakPoints() != null) {
            for (WeakPointDto weakPoint : dto.weakPoints()) {
                if (isBlank(weakPoint.knowledgePoint()) || isBlank(weakPoint.reason())) {
                    throw new IllegalArgumentException("weakPoints 的 knowledgePoint / reason 不得为空");
                }
            }
        }
    }

    /** 聚合规则在代码不在 LLM（红线 3）：均值代码算、≤2 分知识点强制入薄弱点 */
    private EvaluationReport aggregate(InterviewSession session, ReportDto dto) {
        List<QuestionScore> perQuestion = dto.perQuestion().stream()
                .map(p -> new QuestionScore(p.questionId(), p.score(), p.strength().trim(), p.improvement().trim()))
                .toList();
        double average = Math.round(perQuestion.stream()
                .mapToInt(QuestionScore::score)
                .average()
                .orElse(0) * 10.0) / 10.0;

        List<WeakPoint> weakPoints = new ArrayList<>(dto.weakPoints() == null ? List.of() : dto.weakPoints().stream()
                .map(w -> new WeakPoint(w.knowledgePoint().trim(), w.reason().trim()))
                .toList());
        Map<String, Integer> scoreByQuestionId = new HashMap<>();
        perQuestion.forEach(p -> scoreByQuestionId.put(p.questionId(), p.score()));
        for (Question question : session.questions()) {
            Integer score = scoreByQuestionId.get(question.id());
            boolean alreadyListed = weakPoints.stream()
                    .anyMatch(w -> w.knowledgePoint().equals(question.knowledgePoint()));
            if (score != null && score <= 2 && !alreadyListed) {
                weakPoints.add(new WeakPoint(question.knowledgePoint(),
                        "第 " + question.index() + " 题得分 " + score + "（≤2），按规则强制标记为薄弱点"));
            }
        }
        return new EvaluationReport(session.id(), perQuestion, average, dto.summary().trim(),
                List.copyOf(weakPoints), dto.reviewAdvice().stream().map(String::trim).toList());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReportDto(List<ScoreDto> perQuestion, String summary,
                     List<WeakPointDto> weakPoints, List<String> reviewAdvice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScoreDto(String questionId, int score, String strength, String improvement) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WeakPointDto(String knowledgePoint, String reason) {
    }
}
