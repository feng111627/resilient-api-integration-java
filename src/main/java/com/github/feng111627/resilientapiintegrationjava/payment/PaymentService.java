package com.github.feng111627.resilientapiintegrationjava.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.feng111627.resilientapiintegrationjava.error.ErrorKind;
import com.github.feng111627.resilientapiintegrationjava.error.UpstreamException;
import com.github.feng111627.resilientapiintegrationjava.idempotency.IdempotencyStore;
import com.github.feng111627.resilientapiintegrationjava.resilience.CircuitBreaker;
import com.github.feng111627.resilientapiintegrationjava.resilience.RetryPolicy;
import com.github.feng111627.resilientapiintegrationjava.upstream.UpstreamClient;
import com.github.feng111627.resilientapiintegrationjava.upstream.UpstreamRequest;
import com.github.feng111627.resilientapiintegrationjava.upstream.UpstreamResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 支付服务，调用顺序：幂等 -> 熔断 -> 重试 -> 上游。
 * 重试时携带同一 Idempotency-Key 头，上游可据此去重，保证重试不重复生效。
 */
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UpstreamClient upstreamClient;
    private final IdempotencyStore idempotencyStore;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker circuitBreaker;

    public PaymentService(UpstreamClient upstreamClient, IdempotencyStore idempotencyStore,
                          RetryPolicy retryPolicy, CircuitBreaker circuitBreaker) {
        this.upstreamClient = upstreamClient;
        this.idempotencyStore = idempotencyStore;
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = circuitBreaker;
    }

    public PaymentResult createPayment(String idempotencyKey, PaymentCommand command) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空");
        }
        String fingerprint = fingerprint(command);
        log.info("[payment] 创建支付 key={} amount={} {}", idempotencyKey, command.amount(), command.currency());
        return idempotencyStore.execute(idempotencyKey, fingerprint,
                () -> circuitBreaker.execute(
                        () -> retryPolicy.execute(
                                () -> callUpstream(idempotencyKey, command))));
    }

    private PaymentResult callUpstream(String idempotencyKey, PaymentCommand command) {
        String body;
        try {
            body = MAPPER.writeValueAsString(Map.of(
                    "amount", command.amount(),
                    "currency", command.currency()));
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
        UpstreamResponse response = upstreamClient.post(new UpstreamRequest(
                "/payments", body, Map.of("Idempotency-Key", idempotencyKey)));
        return parse(response);
    }

    private PaymentResult parse(UpstreamResponse response) {
        try {
            JsonNode node = MAPPER.readTree(response.body());
            JsonNode id = node.get("paymentId");
            JsonNode status = node.get("status");
            if (id == null || status == null || !id.isTextual() || !status.isTextual()) {
                throw new UpstreamException(ErrorKind.PARSE_ERROR,
                        "上游响应缺少必要字段: " + abbreviate(response.body()));
            }
            return new PaymentResult(id.asText(), status.asText());
        } catch (UpstreamException e) {
            throw e;
        } catch (Exception e) {
            // 不向调用方透出 Jackson 异常，统一归类为 PARSE_ERROR
            throw new UpstreamException(ErrorKind.PARSE_ERROR,
                    "上游响应解析失败: " + abbreviate(response.body()), null, null, e);
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "<null>";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    static String fingerprint(PaymentCommand command) {
        String canonical = command.amount().stripTrailingZeros().toPlainString()
                + "|" + command.currency();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
