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
}
