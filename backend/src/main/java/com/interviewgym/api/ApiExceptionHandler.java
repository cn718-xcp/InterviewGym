package com.interviewgym.api;

import com.interviewgym.core.LlmOutputParseException;
import com.interviewgym.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * API 异常映射：400 参数非法 / 409 状态冲突 / 404 不存在 / 502 LLM 失败。
 * 错误消息只含固定文案与原因类别，不含用户内容（红线 2）。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> notFound(ResponseStatusException e) {
        return body(HttpStatus.valueOf(e.getStatusCode().value()), e.getReason());
    }

    @ExceptionHandler({LlmException.class, LlmOutputParseException.class})
    public ResponseEntity<Map<String, String>> llmUnavailable(RuntimeException e) {
        // 只记原因类别与根因摘要（消息按设计不含密钥与用户内容，红线 1/2）
        Throwable root = rootCause(e);
        log.warn("LLM 调用失败：{} | 根因 {}: {}", e.getMessage(), root.getClass().getSimpleName(), root.getMessage());
        return body(HttpStatus.BAD_GATEWAY, "面试官暂时离线（" + e.getMessage() + "），请稍后再试");
    }

    /** 录音上传超 multipart 上限 → 400 友好提示（spec: voice-mode.md，前端 5 分钟自动停录的双保险） */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> uploadTooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.BAD_REQUEST, "录音文件过大（上限 20MB），请缩短录音时长");
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
