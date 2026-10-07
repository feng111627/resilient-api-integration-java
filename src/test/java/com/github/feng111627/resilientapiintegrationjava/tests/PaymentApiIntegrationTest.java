package com.github.feng111627.resilientapiintegrationjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.feng111627.resilientapiintegrationjava.tests.stub.StubUpstreamServer;
import com.github.feng111627.resilientapiintegrationjava.tests.stub.StubUpstreamServer.StubResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 业务覆盖：对外 API 契约 —— 错误分类可通过 HTTP 状态码与 error 字段区分，
 * 响应体不透出底层 HTTP/序列化异常类型。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentApiIntegrationTest.class);

    private static StubUpstreamServer stub;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void startStub() {
        stub = new StubUpstreamServer(attempt ->
                StubResponse.ok("{\"paymentId\":\"p-api\",\"status\":\"CREATED\"}"));
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("resilience.upstream-base-url", stub::baseUrl);
        registry.add("resilience.max-attempts", () -> "1");
        registry.add("resilience.read-timeout-ms", () -> "500");
        registry.add("resilience.failure-threshold", () -> "100"); // 各用例独立，避免互相熔断
    }

    private ResponseEntity<Map> post(String key, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.exchange("http://localhost:" + port + "/api/payments",
                HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void successReturns201WithPaymentResult() {
        log.info("[test] 用例：正常创建 -> 201 + 支付结果");
        stub.setBehavior(attempt -> StubResponse.ok("{\"paymentId\":\"p-api\",\"status\":\"CREATED\"}"));
        ResponseEntity<Map> resp = post("api-success", Map.of("amount", 12.34, "currency", "CNY"));

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals("p-api", resp.getBody().get("paymentId"));
        log.info("[test] 通过：{}", resp.getBody());
    }

    @Test
    void missingIdempotencyKeyIs400BadRequest() {
        log.info("[test] 用例：缺少幂等键 -> 400 BAD_REQUEST");
        ResponseEntity<Map> resp = post(null, Map.of("amount", 1, "currency", "CNY"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("BAD_REQUEST", resp.getBody().get("error"));
        log.info("[test] 通过：{}", resp.getBody());
    }

    @Test
    void keyReuseWithDifferentPayloadIs409Conflict() {
        log.info("[test] 用例：同键不同内容 -> 409 IDEMPOTENCY_CONFLICT，与 400 区分");
        stub.setBehavior(attempt -> StubResponse.ok("{\"paymentId\":\"p-c\",\"status\":\"CREATED\"}"));
        post("api-conflict", Map.of("amount", 10, "currency", "CNY"));
        ResponseEntity<Map> resp = post("api-conflict", Map.of("amount", 99, "currency", "CNY"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", resp.getBody().get("error"));
        log.info("[test] 通过：{}", resp.getBody());
    }

    @Test
    void upstream5xxIs502WithUpstreamErrorKind() {
        log.info("[test] 用例：上游 500 -> 502 UPSTREAM_ERROR");
        stub.setBehavior(attempt -> StubResponse.status(500, "{\"error\":\"boom\"}"));
        ResponseEntity<Map> resp = post("api-5xx", Map.of("amount", 1, "currency", "CNY"));

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertEquals("UPSTREAM_ERROR", resp.getBody().get("error"));
        assertNotNull(resp.getBody().get("requestId"), "应保留排查现场信息");
        log.info("[test] 通过：{}", resp.getBody());
    }

    @Test
    void upstreamTimeoutIs504() {
        log.info("[test] 用例：上游超时 -> 504 TIMEOUT");
        stub.setBehavior(attempt -> StubResponse.ok("{}").withDelay(3000));
        ResponseEntity<Map> resp = post("api-timeout", Map.of("amount", 1, "currency", "CNY"));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, resp.getStatusCode());
        assertEquals("TIMEOUT", resp.getBody().get("error"));
        log.info("[test] 通过：{}", resp.getBody());
    }

    @Test
    void malformedUpstreamBodyIs502ParseError() {
        log.info("[test] 用例：上游坏 JSON -> 502 PARSE_ERROR，不透出 Jackson 异常");
        stub.setBehavior(attempt -> StubResponse.ok("<<broken>>"));
        ResponseEntity<Map> resp = post("api-parse", Map.of("amount", 1, "currency", "CNY"));

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertEquals("PARSE_ERROR", resp.getBody().get("error"));
        String message = String.valueOf(resp.getBody().get("message"));
        assertEquals(false, message.contains("Jackson") || message.contains("JsonParse"),
                "错误信息不应透出序列化库类型");
        log.info("[test] 通过：{}", resp.getBody());
    }
}
