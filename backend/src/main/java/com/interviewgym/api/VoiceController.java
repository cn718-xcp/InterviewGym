package com.interviewgym.api;

import com.interviewgym.llm.LlmClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 语音作答端点（spec: voice-mode.md）。无状态：音频转文本后即弃，不绑会话、不落库（D015）；
 * 转写文本由前端回填输入框，走既有作答流程。不记录音频与转写内容日志（红线 2）。
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final LlmClient llmClient;

    public VoiceController(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** GET /api/voice → 语音功能是否可用（asr-model 是否已配置），前端据此显隐麦克风 */
    @GetMapping
    public Map<String, Boolean> capability() {
        return Map.of("enabled", llmClient.asrConfigured());
    }

    /** POST /api/voice/transcribe（multipart file）→ {text}。音频只在内存过一道（D015） */
    @PostMapping("/transcribe")
    public Map<String, String> transcribe(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("录音内容为空，请先录音再转写");
        }
        try {
            return Map.of("text", llmClient.transcribe(read(file), file.getOriginalFilename(), file.getContentType()));
        } catch (IllegalStateException e) {
            // 配置缺失 → 400（全局把 IllegalStateException 映射为 409，语义不符；同 ResumeIndexService 的转换口径）
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("录音读取失败，请重试");
        }
    }
}
