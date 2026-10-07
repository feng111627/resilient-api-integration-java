package com.github.feng111627.resilientapiintegrationjava.tests;

import com.github.feng111627.resilientapiintegrationjava.stub.StubBehavior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static com.github.feng111627.resilientapiintegrationjava.tests.IdempotencyConcurrencyTest.extract;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务：支付下单失败后的幂等保护。
 * 覆盖：首个请求失败（可能已在上游超时但实际生效）后，重复提交不会再次打上游，
 * 而是返回可区分的失败重放码 IDEMPOTENCY_PREVIOUS_FAILED，避免重复副作用；
 * 换用新幂等键才允许重新发起。
 */
@DisplayName("业务-支付下单：失败后不自动重试，防止重复副作用")
class FailedRequestIdempotencyTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(FailedRequestIdempotencyTest.class);

    private final ApiClient client = new ApiClient();
    private static final String BODY =
            "{\"amount\":8.88,\"payerAccount\":\"A\",\"payeeAccount\":\"B\"}";

    @Test
    @DisplayName("首个请求超时失败后，同键再提交返回 409 IDEMPOTENCY_PREVIOUS_FAILED 且不再调上游")
    void replayFailure_thenNewKeyAllowed() {
        String key = "fail-idem-" + UUID.randomUUID();
        registry.setRule(key, StubBehavior.DELAY_FOREVER);

        ApiClient.Response first = client.postPayment(baseUrl(), key, BODY);
        int callsAfterFirst = registry.callCountOf(key);
        log.info("首次失败：status={} code={} 上游次数={}",
                first.status(), extract(first.body(), "code"), callsAfterFirst);
        assertThat(first.status()).isEqualTo(503);

        ApiClient.Response duplicate = client.postPayment(baseUrl(), key, BODY);
        log.info("同键重复：status={} code={} 上游次数增量={}",
                duplicate.status(), extract(duplicate.body(), "code"),
                registry.callCountOf(key) - callsAfterFirst);

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(extract(duplicate.body(), "code")).isEqualTo("IDEMPOTENCY_PREVIOUS_FAILED");
        assertThat(duplicate.body()).contains("originalErrorCode");
        assertThat(registry.callCountOf(key))
                .as("失败后的重复提交不得再次调用上游")
                .isEqualTo(callsAfterFirst);

        // 使用新键、上游恢复正常后应能成功
        String newKey = "fail-idem-retry-" + UUID.randomUUID();
        registry.setRule(newKey, StubBehavior.OK);
        ApiClient.Response retryWithNewKey = client.postPayment(baseUrl(), newKey, BODY);
        log.info("新键重试：status={}", retryWithNewKey.status());
        assertThat(retryWithNewKey.status()).isEqualTo(201);
    }
}
