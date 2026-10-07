package com.github.feng111627.resilientapiintegrationjava.web;

import com.github.feng111627.resilientapiintegrationjava.exception.IntegrationException;
import com.github.feng111627.resilientapiintegrationjava.model.ApiError;
import com.github.feng111627.resilientapiintegrationjava.model.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理。所有对外错误都使用 {@link ApiError} 统一结构与稳定 {@link ErrorCode}，
 * 底层 HTTP / 序列化异常不会透传到响应体。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ResponseEntity<ApiError> handleMissingKey(MissingIdempotencyKeyException e) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_IDEMPOTENCY_KEY,
                e.getMessage(), null, Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "请求参数校验失败", null, Map.of("fields", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "请求体不是合法的 JSON", null, Map.of());
    }

    @ExceptionHandler(IntegrationException.class)
    public ResponseEntity<ApiError> handleIntegration(IntegrationException e) {
        HttpStatus status = switch (e.getErrorCode()) {
            case VALIDATION_ERROR, MISSING_IDEMPOTENCY_KEY -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_CONFLICT, IDEMPOTENCY_PREVIOUS_FAILED,
                 IDEMPOTENCY_IN_FLIGHT -> HttpStatus.CONFLICT;
            case UPSTREAM_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case UPSTREAM_ERROR, UPSTREAM_PARSE_ERROR -> HttpStatus.BAD_GATEWAY;
            case CIRCUIT_OPEN, UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        String key = (String) e.getDetails().get("idempotencyKey");
        log.warn("集成异常 code={} status={} message={}", e.getErrorCode(), status, e.getMessage());
        return build(status, e.getErrorCode(), e.getMessage(), key, e.getDetails());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        // 只记录日志用于排查，对外返回脱敏的通用信息
        log.error("未预期异常", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "系统内部错误，请稍后重试", null, Map.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, ErrorCode code, String message,
                                           String idempotencyKey, Map<String, Object> details) {
        ApiError body = ApiError.of(code, message, idempotencyKey, details);
        return ResponseEntity.status(status).body(body);
    }
}
