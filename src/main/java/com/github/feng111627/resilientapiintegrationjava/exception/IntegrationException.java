package com.github.feng111627.resilientapiintegrationjava.exception;

import com.github.feng111627.resilientapiintegrationjava.model.ErrorCode;

import java.util.Collections;
import java.util.Map;

/**
 * 统一业务集成异常。所有上游失败都被转换为本异常，
 * 携带稳定的 {@link ErrorCode} 与脱敏后的诊断细节。
 * 不得直接把底层 HTTP / 序列化库异常透传给调用方。
 */
public class IntegrationException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;
    private final boolean retryable;

    public IntegrationException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, Collections.emptyMap(), false);
    }

    public IntegrationException(ErrorCode errorCode, String message, boolean retryable) {
        this(errorCode, message, null, Collections.emptyMap(), retryable);
    }

    public IntegrationException(ErrorCode errorCode, String message, Throwable cause,
                                Map<String, Object> details, boolean retryable) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details == null ? Collections.emptyMap() : Collections.unmodifiableMap(details);
        this.retryable = retryable;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
