package com.interviewgym.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewgym.llm.LlmClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * 出题引擎：岗位 JD → 10 题模拟面试（spec: docs/specs/question-generation.md）。
 * System/User prompt 与题目构成为红线 3 保护对象，改动需重新走人类确认流程。
 */
@Component
public class QuestionGenerationService {

    private static final int QUESTION_COUNT = 10;
    private static final Set<String> DIFFICULTIES = Set.of("BASIC", "MEDIUM", "HARD");

    /** 红线 3 定稿模板（D009，2026-10-25 人类确认） */
    private static final String SYSTEM_PROMPT =
            "你是一名资深的技术面试官，有十年以上后端与系统设计面试经验。"
                    + "你的任务是根据候选人目标岗位的 JD，出一场高质量的模拟面试。";

    private static final String USER_PROMPT_TEMPLATE = """
            以下是候选人目标岗位的 JD：
            %s

            出题规则：
            1. 先从 JD 中提取核心技术要求（语言、框架、中间件、领域知识）
            2. 出 10 题：6 题八股（type=FUNDAMENTAL，基础知识）+ 2 题项目深挖（type=PROJECT，围绕该岗位典型项目经历的开放追问）+ 2 题场景设计（type=SCENARIO，真实工作场景问题），覆盖 JD 核心技术点
            3. 难度比例：基础(BASIC):中等(MEDIUM):较难(HARD) 约为 4:4:2
            4. 每题附 knowledgePoint 知识点标签（如 "MySQL 索引"、"并发编程"）
            5. 只输出严格 JSON，无任何其他文字或 markdown 围栏：
            {"questions":[{"index":1,"type":"FUNDAMENTAL|PROJECT|SCENARIO","difficulty":"BASIC|MEDIUM|HARD","knowledgePoint":"...","content":"..."}]}
            """;

    private static final String REPAIR_SUFFIX_TEMPLATE =
            "\n\n你上一次的输出不是合法 JSON 或不符合 schema（原因：%s）。请严格按 schema 只输出 JSON。";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public QuestionGenerationService(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * @throws LlmOutputParseException 修复重试 1 次后仍不合法
     * @throws com.interviewgym.llm.LlmException LLM 端点失败（向上传播，API 层转 502）
     */
    public List<Question> generate(String jdText) {
        String userPrompt = USER_PROMPT_TEMPLATE.formatted(jdText);
        String raw = llmClient.chat(SYSTEM_PROMPT, userPrompt);
        try {
            return parseQuestions(raw);
        } catch (IllegalArgumentException firstFailure) {
            String repairPrompt = userPrompt + REPAIR_SUFFIX_TEMPLATE.formatted(firstFailure.getMessage());
            String repaired = llmClient.chat(SYSTEM_PROMPT, repairPrompt);
            try {
                return parseQuestions(repaired);
            } catch (IllegalArgumentException secondFailure) {
                throw new LlmOutputParseException("出题结果解析失败，重试后仍不合法：" + secondFailure.getMessage());
            }
        }
    }

    private List<Question> parseQuestions(String raw) {
        QuestionsPayload payload;
        try {
            payload = objectMapper.readValue(LlmJsonExtractor.extractJsonObject(raw), QuestionsPayload.class);
        } catch (IllegalArgumentException | JacksonException e) {
            throw new IllegalArgumentException("输出不是合法 JSON");
        }
        List<QuestionDto> dtos = payload.questions();
        if (dtos == null || dtos.size() != QUESTION_COUNT) {
            throw new IllegalArgumentException("题目数量应为 10，实际为 " + (dtos == null ? 0 : dtos.size()));
        }
        List<QuestionDto> sorted = dtos.stream()
                .sorted(Comparator.comparingInt(QuestionDto::index))
                .toList();
        boolean indexesContinuous = IntStream.rangeClosed(1, QUESTION_COUNT)
                .allMatch(i -> sorted.get(i - 1).index() == i);
        if (!indexesContinuous) {
            throw new IllegalArgumentException("index 必须为 1..10 连续");
        }
        List<Question> questions = new ArrayList<>(QUESTION_COUNT);
        for (QuestionDto dto : sorted) {
            QuestionType type = parseType(dto);
            String difficulty = dto.difficulty() == null ? "" : dto.difficulty().trim();
            if (!DIFFICULTIES.contains(difficulty)) {
                throw new IllegalArgumentException(
                        "第 " + dto.index() + " 题难度非法，只允许 BASIC/MEDIUM/HARD");
            }
            if (isBlank(dto.knowledgePoint()) || isBlank(dto.content())) {
                throw new IllegalArgumentException("第 " + dto.index() + " 题 knowledgePoint/content 不得为空");
            }
            questions.add(new Question(UUID.randomUUID().toString(), dto.index(), type,
                    difficulty, dto.knowledgePoint().trim(), dto.content().trim()));
        }
        return questions;
    }

    private static QuestionType parseType(QuestionDto dto) {
        String type = dto.type() == null ? "" : dto.type().trim();
        try {
            return QuestionType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "第 " + dto.index() + " 题题型非法，只允许 FUNDAMENTAL/PROJECT/SCENARIO");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuestionsPayload(List<QuestionDto> questions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuestionDto(int index, String type, String difficulty, String knowledgePoint, String content) {
    }
}
