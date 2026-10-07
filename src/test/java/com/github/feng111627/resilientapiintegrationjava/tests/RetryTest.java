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
 * 业务：支付下单重试。
 * 覆盖：可安全重试的瞬时失败做有限退避重试且最终只成功一次；
 * 不可重试错误（解析失败、4xx）立即失败、不产生重复调用；重试耗尽分类为 UPSTREAM_UNAVAILABLE。
 */
@DisplayName("业务-支付下单：超时与重试")
class RetryTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RetryTest.class);

    private final ApiClient client = new ApiClient();
    private static final String BODY =
            "{\"amount\":3.00,\"payerAccount\":\"A\",\"payeeAccount\":\"B\"}";

    @Test
    @DisplayName("前 2 次 500、第 3 次成功：共 3 次调用，最终 201，重试不重复生效")
    void transientFailure_retriesAndSucceeds() {
        String key = "retry-ok-" + UUID.randomUUID();
        registry.setRule(key, StubBehavior.FAIL_500, 2);

        ApiClient.Response r = client.postPayment(baseUrl(), key, BODY);
        log.info("瞬时失败重试：status={} 上游次数={} body={}",
                r.status(), registry.callCountOf(key), r.body());

        assertThat(r.status()).isEqualTo(201);
        assertThat(registry.callCountOf(key)).isEqualTo(3);
        assertThat(extract(r.body(), "paymentId")).startsWith("PAY-");
    }

    @Test
    @DisplayName("持续 500：最多 3 次后返回 503 UPSTREAM_UNAVAILABLE")
    void persistentFailure_exhaustsRetries() {
        String key = "retry-exhaust-" + UUID.randomUUID();
        registry.setRule(key, StubBehavior.FAIL_500);

        ApiClient.Response r = client.postPayment(baseUrl(), key, BODY);
        log.info("持续失败：status={} code={} 上游次数={}",
                r.status(), extract(r.body(), "code"), registry.callCountOf(key));

        assertThat(r.status()).isEqualTo(503);
        assertThat(extract(r.body(), "code")).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(r.body()).contains("\"attempt\":3");
        assertThat(registry.callCountOf(key)).isEqualTo(3);
    }

    @Test
    @DisplayName("上游持续超时：每次 300ms 超时并重试，耗尽后 503（可重试失败）")
    void timeout_isRetriedThenUnavailable() {
        String key = "retry-timeout-" + UUID.randomUUID();
        registry.setRule(key, StubBehavior.DELAY_FOREVER);

        long start = System.currentTimeMillis();
        ApiClient.Response r = client.postPayment(baseUrl(), key, BODY);
        long elapsed = System.currentTimeMillis() - start;

        log.info("持续超时：status={} code={} 耗时={}ms 上游次数={}",
                r.status(), extract(r.body(), "code"), elapsed, registry.callCountOf(key));

        assertThat(r.status()).isEqualTo(503);
        // 上游已慢到 5s，但客户端 300ms 即超时；断言不是干等 5s
        assertThat(elapsed).isLessThan(2000);
        assertThat(registry.callCountOf(key)).isEqualTo(3);
    }

    @Test
    @DisplayName("响应体解析失败：不重试，返回 502 UPSTREAM_PARSE_ERROR，只调用一次")
    void parseError_notRetried() {
        String key = "retry-parse-" + UUID.randomUUID();
        registry.setRule(key, StubBehavior.MALFORMED_JSON);

        ApiClient.Response r = client.postPayment(baseUrl(), key, BODY);
        log.info("解析失败：status={} code={} 上游次数={}",
                r.status(), extract(r.body(), "code"), registry.callCountOf(key));

        assertThat(r.status()).isEqualTo(502);
        assertThat(extract(r.body(), "code")).isEqualTo("UPSTREAM_PARSE_ERROR");
        assertThat(registry.callCountOf(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("422 业务错误：不可重试，返回 502 UPSTREAM_ERROR，只调用一次")
    void clientError_notRetried() {
        String key = "retry-422-" + UUID.randomUUID();
        registry.setRule(key, StubBehavior.REJECT_422);

        ApiClient.Response r = client.postPayment(baseUrl(), key, BODY);
        log.info("422：status={} code={} 上游次数={}",
                r.status(), extract(r.body(), "code"), registry.callCountOf(key));

        assertThat(r.status()).isEqualTo(502);
        assertThat(extract(r.body(), "code")).isEqualTo("UPSTREAM_ERROR");
        assertThat(registry.callCountOf(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("429 限流：可重试，最终成功（前 1 次 429）")
    void rateLimited_retried() {
        String key = "retry-429-" + UUID.randomUUID();
        registry.setRule(key, StubBehavior.FAIL_429, 1);

        ApiClient.Response r = client.postPayment(baseUrl(), key, BODY);
        log.info("429 重试：status={} 上游次数={}", r.status(), registry.callCountOf(key));

        assertThat(r.status()).isEqualTo(201);
        assertThat(registry.callCountOf(key)).isEqualTo(2);
    }
}
