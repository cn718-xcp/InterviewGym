package com.interviewgym.api;

import com.interviewgym.config.LlmProperties;
import com.interviewgym.llm.LlmClient;
import com.interviewgym.llm.LlmException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 语音端点（spec: voice-mode.md）：asr-model 已配置的上下文（enabled=true）。
 * LlmClient 全程 mock，测试与 CI 永不调用真实端点（spec: llm-client.md 不变式）。
 */
@SpringBootTest(properties = "llm.asr-model=SenseVoiceSmall")
@AutoConfigureMockMvc
class VoiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmClient llmClient;

    @Autowired
    private LlmProperties llmProperties;

    @Autowired
    private ApiExceptionHandler exceptionHandler;

    @Test
    void capabilityEnabledWhenAsrModelConfigured() throws Exception {
        // LlmClient 是 mock，enabled 由 stub 决定；属性绑定（yml/properties → LlmProperties）单独用真实 bean 验证
        when(llmClient.asrConfigured()).thenReturn(true);
        assertThat(llmProperties.getAsrModel()).isEqualTo("SenseVoiceSmall");

        mockMvc.perform(get("/api/voice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void transcribeReturnsText() throws Exception {
        when(llmClient.transcribe(any(), any(), any())).thenReturn("索引底层是 B+ 树");

        mockMvc.perform(multipart("/api/voice/transcribe")
                        .file(new MockMultipartFile("file", "answer.webm", "audio/webm",
                                "AUDIO-BYTES".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("索引底层是 B+ 树"));

        // 音频字节、文件名、content-type 原样透传给客户端
        ArgumentCaptor<byte[]> audio = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> filename = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
        verify(llmClient).transcribe(audio.capture(), filename.capture(), contentType.capture());
        assertThat(new String(audio.getValue(), StandardCharsets.UTF_8)).isEqualTo("AUDIO-BYTES");
        assertThat(filename.getValue()).isEqualTo("answer.webm");
        assertThat(contentType.getValue()).isEqualTo("audio/webm");
    }

    @Test
    void transcribeRejectsEmptyFile() throws Exception {
        mockMvc.perform(multipart("/api/voice/transcribe")
                        .file(new MockMultipartFile("file", "answer.webm", "audio/webm", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("录音内容为空，请先录音再转写"));
    }

    /** 缺 file part 也须回同构 {"error":...} 400（审查轮补：默认走 Spring 原生错误体，与其余错误口径不同构） */
    @Test
    void transcribeRejectsMissingFilePart() throws Exception {
        mockMvc.perform(multipart("/api/voice/transcribe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少录音文件（file），请重新录音再试"));
    }

    @Test
    void transcribeReturns400WhenAsrModelUnconfigured() throws Exception {
        // LlmClient 的配置缺失异常在控制器转 400（同 ResumeIndexService 口径，spec: voice-mode.md）
        when(llmClient.transcribe(any(), any(), any()))
                .thenThrow(new IllegalStateException("转写模型未配置，请设置环境变量 LLM_ASR_MODEL"));

        mockMvc.perform(multipart("/api/voice/transcribe")
                        .file(new MockMultipartFile("file", "answer.webm", "audio/webm",
                                "AUDIO".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("转写模型未配置，请设置环境变量 LLM_ASR_MODEL"));
    }

    @Test
    void transcribeReturns502WhenAsrEndpointFails() throws Exception {
        when(llmClient.transcribe(any(), any(), any()))
                .thenThrow(new LlmException("LLM 端点不可达或超时"));

        mockMvc.perform(multipart("/api/voice/transcribe")
                        .file(new MockMultipartFile("file", "answer.webm", "audio/webm",
                                "AUDIO".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadGateway());
    }

    /** MockMvc 无真实容器解析，multipart 上限映射直测 handler（spec: voice-mode.md 失败与降级） */
    @Test
    void uploadLimitMapsToFriendly400() {
        var response = exceptionHandler.uploadTooLarge(new MaxUploadSizeExceededException(20 * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "录音文件过大（上限 20MB），请缩短录音时长");
    }
}
