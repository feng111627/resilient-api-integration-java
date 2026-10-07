package com.github.feng111627.resilientapiintegrationjava.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 支付下单请求（示例业务）。
 *
 * @param amount       金额，必须为正
 * @param payerAccount 付款方账号
 * @param payeeAccount 收款方账号
 */
public record PaymentRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String payerAccount,
        @NotBlank String payeeAccount) {
}
