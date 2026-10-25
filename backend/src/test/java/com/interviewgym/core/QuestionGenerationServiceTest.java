package com.interviewgym.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewgym.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionGenerationServiceTest {

    private static final String JD = "招聘 Java 后端工程师：熟悉 Java 并发、JVM 调优、Spring Boot、MySQL、Redis、消息队列。";

    private LlmClient llmClient;
    private QuestionGenerationService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        service = new QuestionGenerationService(llmClient, new ObjectMapper());
    }

    private static String questionEntry(int index, String type, String difficulty) {
        return "{\"index\":" + index + ",\"type\":" + type + ",\"difficulty\":" + difficulty
                + ",\"knowledgePoint\":\"KP" + index + "\",\"content\":\"第" + index + "题\"}";
    }

    private static String questionsJson(String... entries) {
        return "{\"questions\":[" + String.join(",", entries) + "]}";
    }

    private static String[] tenEntries() {
        return IntStream.rangeClosed(1, 10)
                .mapToObj(i -> questionEntry(i, "\"FUNDAMENTAL\"", "\"BASIC\""))
                .toArray(String[]::new);
    }

    @Test
    void generateReturnsTenQuestionsWithUniqueIds() {
        when(llmClient.chat(anyString(), anyString())).thenReturn(questionsJson(tenEntries()));

        List<Question> result = service.generate(JD);

        assertThat(result).hasSize(10);
        assertThat(result).extracting(Question::id).doesNotHaveDuplicates();
        assertThat(result.get(0).type()).isEqualTo(QuestionType.FUNDAMENTAL);
        assertThat(result.get(0).content()).isEqualTo("第1题");
        verify(llmClient, times(1)).chat(anyString(), anyString());
    }

    @Test
    void generateStripsMarkdownFences() {
        String fenced = "```json\n" + questionsJson(tenEntries()) + "\n```";
        when(llmClient.chat(anyString(), anyString())).thenReturn(fenced);

        assertThat(service.generate(JD)).hasSize(10);
    }

    @Test
    void generateSendsJdAndCompositionRulesInPrompt() {
        when(llmClient.chat(anyString(), anyString())).thenReturn(questionsJson(tenEntries()));

        service.generate(JD);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue())
                .contains(JD)
                .contains("10 题")
                .contains("FUNDAMENTAL")
                .contains("4:4:2");
    }

    @Test
    void generateRetriesOnWrongCountThenSucceeds() {
        String nineQuestions = questionsJson(IntStream.rangeClosed(1, 9)
                .mapToObj(i -> questionEntry(i, "\"FUNDAMENTAL\"", "\"BASIC\""))
                .toArray(String[]::new));
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn(nineQuestions)
                .thenReturn(questionsJson(tenEntries()));

        assertThat(service.generate(JD)).hasSize(10);

        ArgumentCaptor<String> userPrompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(2)).chat(anyString(), userPrompts.capture());
        assertThat(userPrompts.getAllValues().get(1)).contains("题目数量应为 10，实际为 9");
    }

    @Test
    void generateRetriesOnIllegalTypeThenSucceeds() {
        String illegalType = questionsJson(IntStream.rangeClosed(1, 10)
                .mapToObj(i -> questionEntry(i, i == 1 ? "\"八股\"" : "\"PROJECT\"", "\"MEDIUM\""))
                .toArray(String[]::new));
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn(illegalType)
                .thenReturn(questionsJson(tenEntries()));

        assertThat(service.generate(JD)).hasSize(10);

        ArgumentCaptor<String> userPrompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(2)).chat(anyString(), userPrompts.capture());
        assertThat(userPrompts.getAllValues().get(1)).contains("题型非法");
    }

    @Test
    void generateRetriesOnIllegalDifficultyThenSucceeds() {
        String illegalDifficulty = questionsJson(IntStream.rangeClosed(1, 10)
                .mapToObj(i -> questionEntry(i, "\"FUNDAMENTAL\"", i == 7 ? "\"简单\"" : "\"MEDIUM\""))
                .toArray(String[]::new));
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn(illegalDifficulty)
                .thenReturn(questionsJson(tenEntries()));

        assertThat(service.generate(JD)).hasSize(10);

        ArgumentCaptor<String> userPrompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(2)).chat(anyString(), userPrompts.capture());
        assertThat(userPrompts.getAllValues().get(1)).contains("难度非法");
    }

    @Test
    void generateThrowsWhenRetryStillInvalid() {
        String brokenIndexes = questionsJson(IntStream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 11)
                .mapToObj(i -> questionEntry(i, "\"FUNDAMENTAL\"", "\"BASIC\""))
                .toArray(String[]::new));
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn(brokenIndexes)
                .thenReturn("抱歉，这不是 JSON");

        assertThatThrownBy(() -> service.generate(JD))
                .isInstanceOf(LlmOutputParseException.class)
                .hasMessageContaining("重试后仍不合法");
        verify(llmClient, times(2)).chat(anyString(), anyString());
    }

    /** v1 模板逐字节基线（D012：无简历路径不得漂移，保 v1/v2 对比实验口径干净） */
    private static String v1Prompt() {
        return String.join("\n",
                "以下是候选人目标岗位的 JD：",
                JD,
                "",
                "出题规则：",
                "1. 先从 JD 中提取核心技术要求（语言、框架、中间件、领域知识）",
                "2. 出 10 题：6 题八股（type=FUNDAMENTAL，基础知识）+ 2 题项目深挖（type=PROJECT，围绕该岗位典型项目经历的开放追问）"
                        + "+ 2 题场景设计（type=SCENARIO，真实工作场景问题），覆盖 JD 核心技术点",
                "3. 难度比例：基础(BASIC):中等(MEDIUM):较难(HARD) 约为 4:4:2",
                "4. 每题附 knowledgePoint 知识点标签（如 \"MySQL 索引\"、\"并发编程\"）",
                "5. 只输出严格 JSON，无任何其他文字或 markdown 围栏：",
                "{\"questions\":[{\"index\":1,\"type\":\"FUNDAMENTAL|PROJECT|SCENARIO\","
                        + "\"difficulty\":\"BASIC|MEDIUM|HARD\",\"knowledgePoint\":\"...\",\"content\":\"...\"}]}",
                "");
    }

    @Test
    void generateWithoutResumeSendsV1PromptByteForByte() {
        when(llmClient.chat(anyString(), anyString())).thenReturn(questionsJson(tenEntries()));

        service.generate(JD);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue()).isEqualTo(v1Prompt());
    }

    @Test
    void blankResumeContextFallsBackToV1Prompt() {
        when(llmClient.chat(anyString(), anyString())).thenReturn(questionsJson(tenEntries()));

        assertThat(service.generate(JD, "  ")).hasSize(10);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue()).isEqualTo(v1Prompt());
    }

    @Test
    void generateWithResumeInjectsContextAndV2Rules() {
        String resumeContext = "### 项目经历\n做过 AI 平台与撮合交易平台";
        when(llmClient.chat(anyString(), anyString())).thenReturn(questionsJson(tenEntries()));

        service.generate(JD, resumeContext);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue())
                .contains("【岗位 JD】")
                .contains(JD)
                .contains("【候选人简历（检索片段）】")
                .contains(resumeContext)
                .contains("必须围绕【候选人简历】中出现过的真实项目出题")
                .contains("八股题优先覆盖 JD 技术要求与候选人简历技能的交集")
                .doesNotContain("围绕该岗位典型项目经历的开放追问");
    }
}
