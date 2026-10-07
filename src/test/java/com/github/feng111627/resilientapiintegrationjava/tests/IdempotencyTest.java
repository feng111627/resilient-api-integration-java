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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 业务覆盖：幂等键 —— 并发重复提交、完成后重放、键复用冲突。 */
class IdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);
    private static final PaymentCommand CMD =
            new PaymentCommand(new BigDecimal("99.50"), "CNY");

    private StubUpstreamServer stub;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        // 桩加 150ms 延迟，制造并发窗口
        stub = new StubUpstreamServer(attempt ->
                StubResponse.ok("{\"paymentId\":\"p-1\",\"status\":\"CREATED\"}").withDelay(150));
        service = TestServices.paymentService(stub.baseUrl(), 2000, 1, 10, 1000);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void concurrentDuplicateSubmissionsCauseSingleUpstreamSideEffect() throws Exception {
        log.info("[test] 用例：同键并发提交 -> 上游只执行一次，所有线程拿到同一结果");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<PaymentResult>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> service.createPayment("key-concurrent", CMD));
        }
        List<Future<PaymentResult>> futures = pool.invokeAll(jobs);
        pool.shutdown();

        for (Future<PaymentResult> f : futures) {
            assertEquals(new PaymentResult("p-1", "CREATED"), f.get());
        }
        assertEquals(1, stub.attemptCount(), "并发下同键只能有一次上游调用");
        assertEquals(1, stub.sideEffectCount(), "上游副作用只能发生一次");
        log.info("[test] 通过：attempts={} sideEffects={}", stub.attemptCount(), stub.sideEffectCount());
    }

    @Test
    void replayAfterCompletionReturnsCachedResultWithoutUpstreamCall() {
        log.info("[test] 用例：完成后同键重放 -> 返回缓存结果，不再调用上游");
        PaymentResult first = service.createPayment("key-replay", CMD);
        PaymentResult second = service.createPayment("key-replay", CMD);

        assertEquals(first, second);
        assertEquals(1, stub.attemptCount());
        log.info("[test] 通过：attempts={}", stub.attemptCount());
    }

    @Test
    void keyReuseWithDifferentPayloadIsRejectedAsConflict() {
        log.info("[test] 用例：同键不同内容 -> IDEMPOTENCY_CONFLICT，区别于普通参数错误");
        service.createPayment("key-conflict", CMD);

        UpstreamException e = assertThrows(UpstreamException.class, () ->
                service.createPayment("key-conflict",
                        new PaymentCommand(new BigDecimal("1.00"), "CNY")));
        assertEquals(ErrorKind.IDEMPOTENCY_CONFLICT, e.kind());

        // 普通参数错误是另一种类型（IllegalArgumentException -> 400）
        assertThrows(IllegalArgumentException.class,
                () -> service.createPayment(" ", CMD));
        log.info("[test] 通过：冲突 kind={}", e.kind());
    }
}
