package com.interviewgym.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewgym.llm.LlmClient;
import com.interviewgym.store.SqliteTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EvaluationServiceTest {

    private static final String JD = "招聘 Java 后端工程师：熟悉 Java 并发、JVM 调优、Spring Boot、MySQL、Redis。";
    private static final int[] ALL_FOURS = {4, 4, 4, 4, 4, 4, 4, 4, 4, 4};

    private LlmClient llmClient;
    private com.interviewgym.store.InterviewStore store;
    private EvaluationService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        store = SqliteTestSupport.open(tempDir.resolve("eval.db"));
        service = new EvaluationService(llmClient, new ObjectMapper().findAndRegisterModules(), store);
    }

    private static InterviewSession answeredSession() {
        List<Question> questions = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> new Question("q-" + i, i, QuestionType.FUNDAMENTAL, "BASIC",
                        "KP" + i, "第" + i + "题题面"))
                .toList();
        InterviewSession session = new InterviewSession(UUID.randomUUID().toString(), JD,
                SessionStatus.ACTIVE, questions, Map.of(), Instant.now());
        for (Question question : questions) {
            session = session.withAnswer(
                    new Answer(question.id(), "对第" + question.index() + "题的回答", Instant.now()));
        }
        return session;
    }

    private static String scoreEntry(String questionId, int score) {
        return "{\"questionId\":\"" + questionId + "\",\"score\":" + score
                + ",\"strength\":\"亮点\",\"improvement\":\"改进方向\"}";
    }

    private static String reportJson(int[] scores, String weakPointsJson) {
        String perQuestion = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> scoreEntry("q-" + i, scores[i - 1]))
                .collect(Collectors.joining(","));
        return "{\"perQuestion\":[" + perQuestion + "],\"summary\":\"总体不错\","
                + "\"weakPoints\":" + weakPointsJson + ",\"reviewAdvice\":[\"建议一\",\"建议二\"]}";
    }

    @Test
    void evaluateComputesAverageInCodeAndPersistsResult() {
        // 5+4+4+3+2+1+5+4+3+2 = 33 → 均值 3.3（代码算，不信 LLM 算术）
        int[] scores = {5, 4, 4, 3, 2, 1, 5, 4, 3, 2};
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn(reportJson(scores, "[{\"knowledgePoint\":\"KP6\",\"reason\":\"答错\"}]"));

        InterviewSession session = answeredSession();
        store.saveSession(session);
        EvaluationReport report = service.evaluate(session);

        assertThat(report.averageScore()).isEqualTo(3.3);
        assertThat(report.perQuestion()).hasSize(10);
        assertThat(report.summary()).isEqualTo("总体不错");
        assertThat(store.findSession(session.id())).hasValueSatisfying(
                s -> assertThat(s.status()).isEqualTo(SessionStatus.EVALUATED));
        assertThat(store.findReport(session.id())).contains(report);
    }

    @Test
    void evaluateForcesLowScoreKnowledgePointsIntoWeakPoints() {
        // q-5 得 2 分、q-6 得 1 分；LLM 只列了 KP5 → KP6 由代码兜底补入
        int[] scores = {4, 4, 4, 4, 2, 1, 4, 4, 4, 4};
        String llmWeakPoints = "[{\"knowledgePoint\":\"KP5\",\"reason\":\"LLM 判断薄弱\"}]";
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn(reportJson(scores, llmWeakPoints));

        InterviewSession session = answeredSession();
        store.saveSession(session);
        EvaluationReport report = service.evaluate(session);

        assertThat(report.weakPoints()).hasSize(2);
        assertThat(report.weakPoints().get(0).knowledgePoint()).isEqualTo("KP5");
        assertThat(report.weakPoints().get(1).knowledgePoint()).isEqualTo("KP6");
        assertThat(report.weakPoints().get(1).reason()).contains("强制标记");
    }

    @Test
    void evaluateRetriesOnScoreOutOfRangeThenSucceeds() {
        int[] outOfRange = {6, 4, 4, 4, 4, 4, 4, 4, 4, 4};
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn(reportJson(outOfRange, "[]"))
                .thenReturn(reportJson(ALL_FOURS, "[]"));

        InterviewSession session = answeredSession();
        store.saveSession(session);
        assertThat(service.evaluate(session).averageScore()).isEqualTo(4.0);

        ArgumentCaptor<String> userPrompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(2)).chat(anyString(), userPrompts.capture());
        assertThat(userPrompts.getAllValues().get(1)).contains("score 应为 1-5");
    }

    @Test
    void evaluateRetriesOnQuestionIdMismatchThenSucceeds() {
        String mismatch = reportJson(ALL_FOURS, "[]")
                .replace("\"questionId\":\"q-1\"", "\"questionId\":\"bogus-id\"");
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn(mismatch)
                .thenReturn(reportJson(ALL_FOURS, "[]"));

        InterviewSession session = answeredSession();
        store.saveSession(session);
        assertThat(service.evaluate(session).perQuestion()).hasSize(10);

        ArgumentCaptor<String> userPrompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(2)).chat(anyString(), userPrompts.capture());
        assertThat(userPrompts.getAllValues().get(1)).contains("questionId 与会话题目不匹配");
    }

    @Test
    void evaluateThrowsWhenNotAllAnswered() {
        InterviewSession session = answeredSession()
                .withStatus(SessionStatus.ACTIVE);
        InterviewSession partial = new InterviewSession(session.id(), session.jdText(), SessionStatus.ACTIVE,
                session.questions().stream().limit(9).toList(), Map.of(), session.createdAt());
        for (Question question : partial.questions()) {
            partial.withAnswer(new Answer(question.id(), "答", Instant.now()));
        }

        assertThatThrownBy(() -> service.evaluate(partial))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未作答");
        verifyNoInteractions(llmClient);
    }

    @Test
    void evaluateThrowsWhenSessionAlreadyEvaluated() {
        InterviewSession session = answeredSession().withStatus(SessionStatus.EVALUATED);

        assertThatThrownBy(() -> service.evaluate(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已评估");
        verifyNoInteractions(llmClient);
    }

    @Test
    void evaluateKeepsAnswersSubmittedAfterItsSnapshot() {
        // 回归测试（AI-FAILURES #6）：评估快照之后提交的答案，不得被评估结束时的整体替换抹掉
        InterviewSession snapshot = answeredSession();
        store.saveSession(snapshot);
        Question firstQuestion = snapshot.questions().get(0);
        store.updateSession(snapshot.id(), s ->
                s.withAnswer(new Answer(firstQuestion.id(), "评估期间更新的新答案", Instant.now())));

        when(llmClient.chat(anyString(), anyString())).thenReturn(reportJson(ALL_FOURS, "[]"));

        service.evaluate(snapshot);

        assertThat(store.findSession(snapshot.id())).hasValueSatisfying(s -> {
            assertThat(s.status()).isEqualTo(SessionStatus.EVALUATED);
            assertThat(s.answers().get(firstQuestion.id()).text()).isEqualTo("评估期间更新的新答案");
        });
    }

    @Test
    void evaluatePromptContainsJdAnswersAndAnchors() {
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn(reportJson(ALL_FOURS, "[]"));

        InterviewSession session = answeredSession();
        store.saveSession(session);
        service.evaluate(session);

        ArgumentCaptor<String> userPrompts = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userPrompts.capture());
        assertThat(userPrompts.getValue())
                .contains(JD)
                .contains("对第3题的回答")
                .contains("评分锚点")
                .contains("5 = 理解透彻")
                .contains("questionId: q-1")
                .contains("知识点: KP1");
    }
}
