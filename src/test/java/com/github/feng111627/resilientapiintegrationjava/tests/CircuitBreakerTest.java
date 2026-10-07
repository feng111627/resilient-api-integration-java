package com.github.feng111627.resilientapiintegrationjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.feng111627.resilientapiintegrationjava.error.ErrorKind;
import com.github.feng111627.resilientapiintegrationjava.error.UpstreamException;
import com.github.feng111627.resilientapiintegrationjava.payment.PaymentCommand;
import com.github.feng111627.resilientapiintegrationjava.payment.PaymentResult;
import com.github.feng111627.resilientapiintegrationjava.payment.PaymentService;
import com.github.feng111627.resilientapiintegrationjava.tests.stub.StubUpstreamServer;
import com.github.feng111627.resilientapiintegrationjava.tests.stub.StubUpstreamServer.StubResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 业务覆盖：熔断 —— 阈值快速失败、冷却试探恢复、已完成结果不被冲掉。 */
class CircuitBreakerTest {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTest.class);
    private static final PaymentCommand CMD =
            new PaymentCommand(new BigDecimal("20.00"), "CNY");
    private static final long COOLDOWN_MS = 400;

    private StubUpstreamServer stub;

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    private PaymentService newService() {
        // maxAttempts=1：让每次业务调用只计一次失败，便于精确控制阈值
        return TestServices.paymentService(stub.baseUrl(), 1000, 1, 3, COOLDOWN_MS);
    }

    @Test
    void opensAfterThresholdAndFailsFastWithoutCallingUpstream() {
        log.info("[test] 用例：连续失败达阈值 -> 熔断打开，后续请求快速失败不打上游");
        stub = new StubUpstreamServer(attempt -> StubResponse.status(500, "{}"));
        PaymentService service = newService();

        for (int i = 0; i < 3; i++) {
            String key = "key-open-" + i;
            UpstreamException e = assertThrows(UpstreamException.class,
                    () -> service.createPayment(key, CMD));
            assertEquals(ErrorKind.UPSTREAM_ERROR, e.kind());
        }
        int attemptsAtOpen = stub.attemptCount();

        UpstreamException fastFail = assertThrows(UpstreamException.class,
                () -> service.createPayment("key-open-blocked", CMD));
        assertEquals(ErrorKind.CIRCUIT_OPEN, fastFail.kind());
        assertEquals(attemptsAtOpen, stub.attemptCount(), "熔断期间不应再调用上游");
        log.info("[test] 通过：熔断后 attempts={} 未增长", stub.attemptCount());
    }

    @Test
    void halfOpenProbeAfterCooldownClosesCircuitOnSuccess() throws Exception {
        log.info("[test] 用例：冷却后试探成功 -> 熔断自动恢复");
        stub = new StubUpstreamServer(attempt -> StubResponse.status(500, "{}"));
        PaymentService service = newService();

        for (int i = 0; i < 3; i++) {
            String key = "key-recover-fail-" + i;
            assertThrows(UpstreamException.class, () -> service.createPayment(key, CMD));
        }
        assertEquals(ErrorKind.CIRCUIT_OPEN, assertThrows(UpstreamException.class,
                () -> service.createPayment("key-recover-blocked", CMD)).kind());

        // 上游恢复 + 等待冷却期满
        stub.setBehavior(attempt -> StubResponse.ok("{\"paymentId\":\"p-r\",\"status\":\"CREATED\"}"));
        Thread.sleep(COOLDOWN_MS + 150);

        PaymentResult result = service.createPayment("key-recover-probe", CMD);
        assertEquals("p-r", result.paymentId());

        // 恢复后正常流量放行
        PaymentResult second = service.createPayment("key-recover-normal", CMD);
        assertEquals("p-r", second.paymentId());
        log.info("[test] 通过：试探成功，熔断关闭，attempts={}", stub.attemptCount());
    }

    @Test
    void failedProbeReopensCircuit() throws Exception {
        log.info("[test] 用例：试探仍失败 -> 重新打开并重新冷却");
        stub = new StubUpstreamServer(attempt -> StubResponse.status(500, "{}"));
        PaymentService service = newService();

        for (int i = 0; i < 3; i++) {
            String key = "key-reopen-fail-" + i;
            assertThrows(UpstreamException.class, () -> service.createPayment(key, CMD));
        }
        Thread.sleep(COOLDOWN_MS + 150);

        // 试探失败
        assertEquals(ErrorKind.UPSTREAM_ERROR, assertThrows(UpstreamException.class,
                () -> service.createPayment("key-reopen-probe", CMD)).kind());
        // 立即再次请求：仍处于熔断，快速失败
        assertEquals(ErrorKind.CIRCUIT_OPEN, assertThrows(UpstreamException.class,
                () -> service.createPayment("key-reopen-blocked", CMD)).kind());
        log.info("[test] 通过：试探失败后保持熔断");
    }

    @Test
    void completedResultsSurviveCircuitOpenAndFailuresDontBlockRetry() {
        log.info("[test] 用例：熔断期间已完成结果仍可重放；失败的键不卡死后续请求");
        // 先用健康上游完成一笔
        stub = new StubUpstreamServer(attempt ->
                StubResponse.ok("{\"paymentId\":\"p-done\",\"status\":\"CREATED\"}"));
        PaymentService service = newService();
        PaymentResult done = service.createPayment("key-done", CMD);
        assertEquals("p-done", done.paymentId());

        // 上游转为持续故障，打爆熔断器
        stub.setBehavior(attempt -> StubResponse.status(500, "{}"));
        for (int i = 0; i < 3; i++) {
            String key = "key-flush-fail-" + i;
            assertThrows(UpstreamException.class, () -> service.createPayment(key, CMD));
        }
        assertEquals(ErrorKind.CIRCUIT_OPEN, assertThrows(UpstreamException.class,
                () -> service.createPayment("key-flush-blocked", CMD)).kind());

        // 熔断期间：已完成键重放仍返回原结果（不被失败冲掉）
        PaymentResult replayed = service.createPayment("key-done", CMD);
        assertEquals(done, replayed);

        // 失败的键没有留下占位：上游恢复后同键可重新成功
        stub.setBehavior(attempt ->
                StubResponse.ok("{\"paymentId\":\"p-new\",\"status\":\"CREATED\"}"));
        // 等冷却
        try {
            Thread.sleep(COOLDOWN_MS + 150);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        PaymentResult retried = service.createPayment("key-flush-fail-0", CMD);
        assertEquals("p-new", retried.paymentId());
        log.info("[test] 通过：完成结果保留，失败键可重试");
    }
}
