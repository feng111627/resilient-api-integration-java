package com.github.feng111627.resilientapiintegrationjava.payment;

import java.math.BigDecimal;

/** 创建支付的业务入参。 */
public record PaymentCommand(BigDecimal amount, String currency) {
}
