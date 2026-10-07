package com.github.feng111627.resilientapiintegrationjava.model;

import java.time.Instant;
import java.util.Map;

/**
 * 对外统一错误响应体。
 *
 * @param code           错误分类码
 * @param message        面向调用方的可读信息
 * @param idempotencyKey 关联的业务幂等键（可能为空）
 * @param details        排查用现场信息（经过脱敏，不含底层异常类名/堆栈）
 * @param time           错误发生时间
 */
public record ApiError(String code, String message, String idempotencyKey,
                       Map<String, Object> details, Instant time) {

    public static ApiError of(ErrorCode code, String message, String idempotencyKey,
                              Map<String, Object> details) {
        return new ApiError(code.name(), message, idempotencyKey,
                details == null ? Map.of() : Map.copyOf(details), Instant.now());
    }
}
