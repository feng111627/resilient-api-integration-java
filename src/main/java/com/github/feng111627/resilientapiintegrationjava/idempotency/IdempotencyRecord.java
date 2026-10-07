package com.github.feng111627.resilientapiintegrationjava.idempotency;

import com.github.feng111627.resilientapiintegrationjava.model.PaymentResult;

import java.time.Instant;

/**
 * 幂等存储中的一条记录。
 */
public record IdempotencyRecord(String key, String requestHash, IdempotencyState state,
                                PaymentResult result, String errorCode, Instant createdAt,
                                Instant completedAt) {
}
