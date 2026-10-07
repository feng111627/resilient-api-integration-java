package com.github.feng111627.resilientapiintegrationjava.stub;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 模拟上游支付服务的本地桩。不访问任何外部网络。
 * 真实副作用计数：每个幂等键只在收到请求时 +1，便于断言“不重复生效”。
 */
@TestComponent
@RestController
@RequestMapping("/api/upstream/payments")
public class UpstreamStubController {

    private static final Logger log = LoggerFactory.getLogger(UpstreamStubController.class);

    private final StubScenarioRegistry registry;

    public UpstreamStubController(StubScenarioRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest servletRequest) {
        StubBehavior behavior = registry.nextBehavior(key);
        log.info("[STUB] 收到上游请求 key={} behavior={} body={}", key, behavior, body);
        return switch (behavior) {
            case OK -> {
                String paymentId = "PAY-" + UUID.randomUUID();
                yield ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"paymentId\":\"" + paymentId + "\",\"status\":\"ACCEPTED\"}");
            }
            case FAIL_500 -> ResponseEntity.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"internal failure\"}");
            case FAIL_429 -> ResponseEntity.status(429)
                    .header("Retry-After", "1")
                    .body("{\"error\":\"rate limited\"}");
            case REJECT_422 -> ResponseEntity.status(422)
                    .body("{\"error\":\"unprocessable\"}");
            case MALFORMED_JSON -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{ this is not valid json ");
            case DELAY_FOREVER -> {
                try {
                    // 远大于测试中配置的客户端超时（300ms）
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                yield ResponseEntity.ok().body("{}");
            }
        };
    }
}
