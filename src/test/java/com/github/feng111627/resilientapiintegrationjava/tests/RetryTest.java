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

/** 业务覆盖：重试 —— 可重试失败退避重试、不可重试失败立即失败、重试不重复生效。 */
class RetryTest {

    private static final Logger log = LoggerFactory.getLogger(RetryTest.class);
    private static final PaymentCommand CMD =
            new PaymentCommand(new BigDecimal("10.00"), "CNY");

    private StubUpstreamServer stub;

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    @Test
    void retryable5xxIsRetriedWithBackoffUntilSuccess() {
        log.info("[test] 用例：上游先 500 两次再 200 -> 重试后成功，副作用只发生一次");
        stub = new StubUpstreamServer(attempt -> attempt <= 2
                ? StubResponse.status(500, "{\"error\":\"boom\"}")
                : StubResponse.ok("{\"paymentId\":\"p-9\",\"status\":\"CREATED\"}"));
        PaymentService service = TestServices.paymentService(stub.baseUrl(), 1000, 3, 10, 1000);

        PaymentResult result = service.createPayment("key-retry-5xx", CMD);

        assertEquals(new PaymentResult("p-9", "CREATED"), result);
        assertEquals(3, stub.attemptCount(), "应重试到第 3 次才成功");
        assertEquals(1, stub.sideEffectCount(), "重试携带同一幂等键，上游副作用只计一次");
        log.info("[test] 通过：attempts={} sideEffects={}", stub.attemptCount(), stub.sideEffectCount());
    }

    @Test
    void timeoutIsRetried() {
        log.info("[test] 用例：首次超时 -> 重试后成功");
        stub = new StubUpstreamServer(attempt -> attempt == 1
                ? StubResponse.ok("{\"paymentId\":\"p-t\",\"status\":\"CREATED\"}").withDelay(2000)
                : StubResponse.ok("{\"paymentId\":\"p-t\",\"status\":\"CREATED\"}"));
        // 读超时 300ms，第一次调用必然超时
        PaymentService service = TestServices.paymentService(stub.baseUrl(), 300, 3, 10, 1000);

        PaymentResult result = service.createPayment("key-retry-timeout", CMD);

        assertEquals("p-t", result.paymentId());
        assertEquals(2, stub.attemptCount(), "超时后应重试一次成功");
        log.info("[test] 通过：attempts={}", stub.attemptCount());
    }

    @Test
    void clientError4xxIsNotRetried() {
        log.info("[test] 用例：上游 400 -> 不重试，直接归为 UPSTREAM_ERROR");
        stub = new StubUpstreamServer(attempt -> StubResponse.status(400, "{\"error\":\"bad request\"}"));
        PaymentService service = TestServices.paymentService(stub.baseUrl(), 1000, 3, 10, 1000);

        UpstreamException e = assertThrows(UpstreamException.class,
                () -> service.createPayment("key-4xx", CMD));

        assertEquals(ErrorKind.UPSTREAM_ERROR, e.kind());
        assertEquals(400, e.upstreamStatus());
        assertEquals(1, stub.attemptCount(), "4xx 不可重试");
        log.info("[test] 通过：kind={} status={} attempts={}", e.kind(), e.upstreamStatus(), stub.attemptCount());
    }

    @Test
    void malformedResponseIsClassifiedAsParseErrorAndNotRetried() {
        log.info("[test] 用例：上游返回坏 JSON -> PARSE_ERROR，不重试，不透出 Jackson 异常");
        stub = new StubUpstreamServer(attempt -> StubResponse.ok("not-a-json{{{"));
        PaymentService service = TestServices.paymentService(stub.baseUrl(), 1000, 3, 10, 1000);

        UpstreamException e = assertThrows(UpstreamException.class,
                () -> service.createPayment("key-parse", CMD));

        assertEquals(ErrorKind.PARSE_ERROR, e.kind());
        assertEquals(1, stub.attemptCount(), "解析失败不可重试");
        log.info("[test] 通过：kind={} msg={}", e.kind(), e.getMessage());
    }

    @Test
    void retriesExhaustedSurfacesLastFailure() {
        log.info("[test] 用例：持续 500 超过最大次数 -> 抛出最后一次失败");
        stub = new StubUpstreamServer(attempt -> StubResponse.status(500, "{}"));
        PaymentService service = TestServices.paymentService(stub.baseUrl(), 1000, 3, 10, 1000);

        UpstreamException e = assertThrows(UpstreamException.class,
                () -> service.createPayment("key-exhaust", CMD));

        assertEquals(ErrorKind.UPSTREAM_ERROR, e.kind());
        assertEquals(3, stub.attemptCount(), "最多尝试 maxAttempts 次");
        log.info("[test] 通过：attempts={}", stub.attemptCount());
    }
}
