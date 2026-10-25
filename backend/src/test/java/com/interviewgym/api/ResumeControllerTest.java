package com.interviewgym.api;

import com.interviewgym.llm.LlmClient;
import com.interviewgym.llm.LlmException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "llm.embedding-model=embedding-3")
@AutoConfigureMockMvc
class ResumeControllerTest {

    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
    private static final String RESUME = "# 技能\n\n" + "甲".repeat(120) + "\n\n# 项目\n\n" + "乙".repeat(120);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmClient llmClient;

    @Test
    void uploadStatusAndDeleteFlow() throws Exception {
        mockMvc.perform(get("/api/resume")).andExpect(status().isNotFound());

        when(llmClient.embed(anyList())).thenAnswer(inv -> {
            List<String> input = inv.getArgument(0);
            return input.stream().map(t -> new float[]{0.1f, 0.2f}).toList();
        });
        mockMvc.perform(put("/api/resume")
                        .contentType(JSON_UTF8)
                        .content("{\"text\":\"" + RESUME.replace("\n", "\\n") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andExpect(jsonPath("$.embedModel").value("embedding-3"))
                .andExpect(jsonPath("$.embedDim").value(2));

        mockMvc.perform(get("/api/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(1));

        mockMvc.perform(delete("/api/resume")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/resume")).andExpect(status().isNotFound());
        // 删除幂等
        mockMvc.perform(delete("/api/resume")).andExpect(status().isNoContent());
    }

    @Test
    void uploadRejectsShortResume() throws Exception {
        mockMvc.perform(put("/api/resume")
                        .contentType(JSON_UTF8)
                        .content("{\"text\":\"太短\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("简历内容太短，至少 100 字"));
    }

    @Test
    void uploadReturns502WhenEmbeddingEndpointFails() throws Exception {
        when(llmClient.embed(anyList())).thenThrow(new LlmException("LLM 端点不可达或超时"));

        mockMvc.perform(put("/api/resume")
                        .contentType(JSON_UTF8)
                        .content("{\"text\":\"" + RESUME.replace("\n", "\\n") + "\"}"))
                .andExpect(status().isBadGateway());
    }
}
