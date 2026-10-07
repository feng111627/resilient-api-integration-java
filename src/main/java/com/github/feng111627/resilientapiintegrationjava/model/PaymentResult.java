package com.github.feng111627.resilientapiintegrationjava.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 下单结果。replayed 表示是否为幂等键命中已完成结果的重放。
 */
public record PaymentResult(String paymentId, String status, BigDecimal amount,
                            String idempotencyKey, boolean replayed, Instant completedAt) {
}
