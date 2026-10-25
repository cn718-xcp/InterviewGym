package com.interviewgym.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 语音关闭态（spec: voice-mode.md 不变式：未配置 LLM_ASR_MODEL → 功能整体关闭，打字主路径不受影响）。
 * 不 mock LlmClient：capability 走真实 asrConfigured()，transcribe 走真实「配置缺失」分支，不发请求。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VoiceCapabilityDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void capabilityDisabledWithoutAsrModel() throws Exception {
        mockMvc.perform(get("/api/voice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void transcribeReturns400WhenDisabled() throws Exception {
        mockMvc.perform(multipart("/api/voice/transcribe").file(
                        new MockMultipartFile("file", "answer.webm", "audio/webm", new byte[16])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("转写模型未配置，请设置环境变量 LLM_ASR_MODEL"));
    }
}
