package com.interviewgym.api;

import com.interviewgym.llm.LlmClient;
import com.interviewgym.llm.LlmException;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InterviewControllerTest {

    private static final String JD = "招聘 Java 后端工程师：熟悉 Java 并发、JVM 调优、Spring Boot、MySQL、Redis、消息队列。";
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmClient llmClient;

    private static String tenQuestionsJson() {
        String entries = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> "{\"index\":" + i
                        + ",\"type\":\"FUNDAMENTAL\",\"difficulty\":\"BASIC\""
                        + ",\"knowledgePoint\":\"KP" + i + "\",\"content\":\"第" + i + "题\"}")
                .collect(Collectors.joining(","));
        return "{\"questions\":[" + entries + "]}";
    }

    private void stubGeneration() {
        when(llmClient.chat(anyString(), anyString())).thenReturn(tenQuestionsJson());
    }

    /** 评估桩必须用会话里真实的 questionId（服务端生成 UUID，创建后才知道） */
    private void stubEvaluation(List<String> questionIds) {
        String perQuestion = questionIds.stream()
                .map(id -> "{\"questionId\":\"" + id + "\",\"score\":4,\"strength\":\"亮点\",\"improvement\":\"改进\"}")
                .collect(Collectors.joining(","));
        when(llmClient.chat(anyString(), anyString()))
                .thenReturn("{\"perQuestion\":[" + perQuestion + "],\"summary\":\"总体不错\","
                        + "\"weakPoints\":[],\"reviewAdvice\":[\"建议一\",\"建议二\"]}");
    }

    private String createSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/interviews")
                        .contentType(JSON_UTF8)
                        .content("{\"jdText\":\"" + JD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(10))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void fullInterviewFlowFromCreateToReport() throws Exception {
        stubGeneration();
        String sessionJson = createSession();
        String sessionId = JsonPath.read(sessionJson, "$.id");
        List<String> questionIds = JsonPath.read(sessionJson, "$.questions[*].id");
        stubEvaluation(questionIds);

        for (String questionId : questionIds) {
            mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                            .contentType(JSON_UTF8)
                            .content("{\"questionId\":\"" + questionId + "\",\"text\":\"我的回答\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answers." + questionId + ".text").value("我的回答"));
        }

        mockMvc.perform(post("/api/interviews/{id}/evaluate", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(4.0))
                .andExpect(jsonPath("$.summary").value("总体不错"));

        mockMvc.perform(get("/api/interviews/{id}/report", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perQuestion.length()").value(10));

        mockMvc.perform(get("/api/interviews/{id}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EVALUATED"));
    }

    @Test
    void createRejectsShortJd() throws Exception {
        mockMvc.perform(post("/api/interviews")
                        .contentType(JSON_UTF8)
                        .content("{\"jdText\":\"太短\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createReturns502WhenLlmFails() throws Exception {
        when(llmClient.chat(anyString(), anyString()))
                .thenThrow(new LlmException("LLM 端点不可达或超时"));

        mockMvc.perform(post("/api/interviews")
                        .contentType(JSON_UTF8)
                        .content("{\"jdText\":\"" + JD + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void unknownSessionReturns404() throws Exception {
        mockMvc.perform(get("/api/interviews/{id}", "not-a-uuid"))
                .andExpect(status().isNotFound());
    }

    @Test
    void answerRejectsUnknownQuestionId() throws Exception {
        stubGeneration();
        String sessionId = JsonPath.read(createSession(), "$.id");

        mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                        .contentType(JSON_UTF8)
                        .content("{\"questionId\":\"bogus\",\"text\":\"回答\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void evaluateBeforeAllAnsweredReturns409() throws Exception {
        stubGeneration();
        String sessionId = JsonPath.read(createSession(), "$.id");

        mockMvc.perform(post("/api/interviews/{id}/evaluate", sessionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void evaluateTwiceReturns409() throws Exception {
        stubGeneration();
        String sessionJson = createSession();
        String sessionId = JsonPath.read(sessionJson, "$.id");
        @SuppressWarnings("unchecked")
        List<String> questionIds = JsonPath.read(sessionJson, "$.questions[*].id");
        stubEvaluation(questionIds);
        for (String questionId : questionIds) {
            mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                            .contentType(JSON_UTF8)
                            .content("{\"questionId\":\"" + questionId + "\",\"text\":\"答\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/interviews/{id}/evaluate", sessionId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/interviews/{id}/evaluate", sessionId))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                        .contentType(JSON_UTF8)
                        .content("{\"questionId\":\"whatever\",\"text\":\"答\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void reportBeforeEvaluateReturns404() throws Exception {
        stubGeneration();
        String sessionId = JsonPath.read(createSession(), "$.id");

        mockMvc.perform(get("/api/interviews/{id}/report", sessionId))
                .andExpect(status().isNotFound());
    }
}
