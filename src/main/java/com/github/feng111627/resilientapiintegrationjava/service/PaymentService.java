package com.github.feng111627.resilientapiintegrationjava.service;

import com.github.feng111627.resilientapiintegrationjava.exception.IntegrationException;
import com.github.feng111627.resilientapiintegrationjava.model.PaymentRequest;
import com.github.feng111627.resilientapiintegrationjava.model.PaymentResult;

/**
 * 编排幂等、重试、熔断的下单入口。
 */
public interface PaymentService {
    PaymentResult createPayment(String idempotencyKey, PaymentRequest request)
            throws IntegrationException;
}
