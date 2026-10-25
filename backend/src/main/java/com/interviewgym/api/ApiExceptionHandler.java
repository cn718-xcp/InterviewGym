package com.interviewgym.api;

import com.interviewgym.core.LlmOutputParseException;
import com.interviewgym.llm.LlmException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * API 异常映射：400 参数非法 / 409 状态冲突 / 404 不存在 / 502 LLM 失败。
 * 错误消息只含固定文案与原因类别，不含用户内容（红线 2）。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

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
        return body(HttpStatus.BAD_GATEWAY, "面试官暂时离线（" + e.getMessage() + "），请稍后再试");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
