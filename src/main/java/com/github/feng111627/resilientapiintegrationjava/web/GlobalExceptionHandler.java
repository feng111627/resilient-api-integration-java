package com.github.feng111627.resilientapiintegrationjava.web;

import com.github.feng111627.resilientapiintegrationjava.error.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常映射：错误分类体现在响应体的 error 字段，
 * 幂等冲突(409) 与普通参数错误(400) 明确区分。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<ErrorResponse> handleUpstream(UpstreamException e) {
        HttpStatus status = switch (e.kind()) {
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;          // 409
            case CIRCUIT_OPEN -> HttpStatus.SERVICE_UNAVAILABLE;       // 503
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;                // 504
            case UPSTREAM_ERROR, PARSE_ERROR -> HttpStatus.BAD_GATEWAY;// 502
        };
        log.warn("[error] kind={} status={} requestId={} msg={}",
                e.kind(), status.value(), e.requestId(), e.getMessage());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(e.kind().name(), e.getMessage(), e.requestId()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        // 普通参数错误：400，与幂等冲突 409 区分
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", e.getMessage(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", "请求体格式错误", null));
    }
}
