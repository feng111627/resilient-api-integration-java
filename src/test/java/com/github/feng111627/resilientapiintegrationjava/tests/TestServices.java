package com.github.feng111627.resilientapiintegrationjava.tests;

import com.github.feng111627.resilientapiintegrationjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.resilientapiintegrationjava.payment.PaymentService;
import com.github.feng111627.resilientapiintegrationjava.resilience.CircuitBreaker;
import com.github.feng111627.resilientapiintegrationjava.resilience.RetryPolicy;
import com.github.feng111627.resilientapiintegrationjava.upstream.HttpUpstreamClient;
import com.github.feng111627.resilientapiintegrationjava.upstream.UpstreamClient;

/** 测试用装配：把调用层接到本地桩上。 */
final class TestServices {

    private TestServices() {
    }

    static PaymentService paymentService(String baseUrl, int readTimeoutMs,
                                         int maxAttempts, int failureThreshold, long cooldownMs) {
        UpstreamClient client = new HttpUpstreamClient(baseUrl, 500, readTimeoutMs);
        return new PaymentService(client, new InMemoryIdempotencyStore(),
                new RetryPolicy(maxAttempts, 20, 2.0),
                new CircuitBreaker(failureThreshold, cooldownMs));
    }
}
