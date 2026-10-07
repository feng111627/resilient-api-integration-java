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
 * 业务：支付下单熔断。
 * 覆盖：连续失败达阈值（5）后 OPEN，期间请求快速失败且不打上游；
 * 冷却（600ms）后 HALF_OPEN 试探，成功即恢复；熔断期间已完成结果仍可重放。
 */
@DisplayName("业务-支付下单：熔断快速失败与自动恢复")
class CircuitBreakerTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTest.class);

    private final ApiClient client = new ApiClient();
    private static final String BODY =
            "{\"amount\":5.00,\"payerAccount\":\"A\",\"payeeAccount\":\"B\"}";

    @Test
    @DisplayName("连续失败打开熔断后快速失败，冷却后试探恢复 CLOSED")
    void opensThenRecovers() throws Exception {
        // 全局持续 500
        registry.setDefaultBehavior(StubBehavior.FAIL_500);
        // 阈值 5，每个请求最多 3 次尝试 => 2 个失败请求即累计 6 次失败并 OPEN
        ApiClient.Response f1 = call("cb-fail-1");
        ApiClient.Response f2 = call("cb-fail-2");
        log.info("前两个请求失败：f1={} f2={}，熔断状态={}",
                f1.status(), f2.status(), circuitBreaker.currentState());
        assertThat(circuitBreaker.currentState().name()).isEqualTo("OPEN");

        int upstreamCallsBeforeFastFail = registry.globalCallCount();
        ApiClient.Response fast = call("cb-fast-fail");
        int upstreamCallsAfterFastFail = registry.globalCallCount();
        log.info("OPEN 期间请求：status={} code={}，上游调用增量={}",
                fast.status(), extract(fast.body(), "code"),
                upstreamCallsAfterFastFail - upstreamCallsBeforeFastFail);

        assertThat(fast.status()).isEqualTo(503);
        assertThat(extract(fast.body(), "code")).isEqualTo("CIRCUIT_OPEN");
        assertThat(fast.body()).contains("remainingOpenMillis");
        assertThat(upstreamCallsAfterFastFail - upstreamCallsBeforeFastFail)
                .as("熔断打开期间不得再调用上游").isZero();

        // 冷却结束，进入 HALF_OPEN；上游恢复为 OK，试探成功 -> CLOSED
        log.info("等待熔断冷却 600ms ...");
        Thread.sleep(700);
        registry.setDefaultBehavior(StubBehavior.OK);
        ApiClient.Response recovered = client.postPayment(baseUrl(), "cb-recover", BODY);
        log.info("冷却后试探：status={} 熔断状态={}",
                recovered.status(), circuitBreaker.currentState());

        assertThat(recovered.status()).isEqualTo(201);
        assertThat(circuitBreaker.currentState().name()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("熔断 OPEN 期间，此前已完成的结果仍可正常重放，不被冲掉")
    void completedResultSurvivesOpenCircuit() throws Exception {
        String key = "cb-survive-" + UUID.randomUUID();
        registry.setRule(key, StubBehavior.OK);
        ApiClient.Response ok = client.postPayment(baseUrl(), key, BODY);
        assertThat(ok.status()).isEqualTo(201);

        // 用其它键把熔断打到 OPEN
        registry.setDefaultBehavior(StubBehavior.FAIL_500);
        call("cb-open-a");
        call("cb-open-b");
        assertThat(circuitBreaker.currentState().name()).isEqualTo("OPEN");

        // 熔断打开，但同键重复提交应命中已完成结果
        ApiClient.Response replay = client.postPayment(baseUrl(), key, BODY);
        log.info("熔断期间重放已完成结果：status={} replayed={} paymentId一致={}",
                replay.status(), extract(replay.body(), "replayed"),
                extract(ok.body(), "paymentId").equals(extract(replay.body(), "paymentId")));

        assertThat(replay.status()).isEqualTo(200);
        assertThat(extract(replay.body(), "replayed")).isEqualTo("true");
        assertThat(extract(replay.body(), "paymentId"))
                .isEqualTo(extract(ok.body(), "paymentId"));
    }

    private ApiClient.Response call(String key) {
        return client.postPayment(baseUrl(), key, BODY);
    }
}
