package com.github.feng111627.resilientapiintegrationjava.client;

import com.github.feng111627.resilientapiintegrationjava.client.json.JsonCodec;
import com.github.feng111627.resilientapiintegrationjava.exception.IntegrationException;
import com.github.feng111627.resilientapiintegrationjava.model.ErrorCode;
import com.github.feng111627.resilientapiintegrationjava.model.PaymentRequest;
import com.github.feng111627.resilientapiintegrationjava.model.PaymentResult;
import com.github.feng111627.resilientapiintegrationjava.resilience.config.ResilienceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上游支付 REST 客户端。
 *
 * <p>契约：</p>
 * <ul>
 *   <li>POST {baseUrl}/api/upstream/payments，请求头携带 Idempotency-Key，JSON 请求体。</li>
 *   <li>成功：HTTP 2xx，响应体含 paymentId/status。</li>
 *   <li>本方法不做重试与熔断，只做一次调用并把一切底层异常归一为
 *       {@link IntegrationException}（retryable 标记是否可安全重试）。</li>
 * </ul>
 */
public class UpstreamPaymentClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamPaymentClient.class);

    /** 上游请求体。 */
    public record UpstreamRequestBody(String idempotencyKey, BigDecimal amount,
                                      String payerAccount, String payeeAccount) {
    }

    /** 上游成功响应体。 */
    public record UpstreamResponse(String paymentId, String status) {
    }

    private final HttpClient httpClient;
    private final UpstreamBaseUrlProvider baseUrlProvider;
    private final Duration requestTimeout;
    private final JsonCodec jsonCodec;

    public UpstreamPaymentClient(HttpClient httpClient, ResilienceProperties.Upstream config) {
        this(httpClient, new UpstreamBaseUrlProvider() {
            @Override
            public String get() {
                return config.getBaseUrl();
            }
        }, config, new JsonCodec());
    }

    public UpstreamPaymentClient(HttpClient httpClient, UpstreamBaseUrlProvider baseUrlProvider,
                                 ResilienceProperties.Upstream config,
                                 JsonCodec jsonCodec) {
        this.httpClient = httpClient;
        this.baseUrlProvider = baseUrlProvider;
        this.requestTimeout = Duration.ofMillis(config.getRequestTimeoutMs());
        this.jsonCodec = jsonCodec;
    }

    public PaymentResult createPayment(String idempotencyKey, PaymentRequest request)
            throws IntegrationException {
        UpstreamRequestBody body = new UpstreamRequestBody(
                idempotencyKey, request.amount(), request.payerAccount(), request.payeeAccount());
        byte[] payload = jsonCodec.writeBytes(body);

        String baseUrl = baseUrlProvider.get();
        long started = System.currentTimeMillis();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/upstream/payments"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new IntegrationException(ErrorCode.UPSTREAM_TIMEOUT,
                    "上游调用超时（>" + requestTimeout.toMillis() + "ms）",
                    null, baseDetails(idempotencyKey, baseUrl,
                            Map.of("timeoutMs", requestTimeout.toMillis())), true);
        } catch (IOException e) {
            // 连接被拒、连接重置等传输层故障：请求大概率未生效，按可重试处理
            Map<String, Object> d = baseDetails(idempotencyKey, baseUrl,
                    Map.of("ioKind", classifyIo(e), "elapsedMs", System.currentTimeMillis() - started));
            throw new IntegrationException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "上游连接不可用: " + classifyIo(e), null, d, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegrationException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "调用上游期间线程被中断", null,
                    baseDetails(idempotencyKey, baseUrl, Map.of("interrupted", true)), false);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            UpstreamResponse parsed = jsonCodec.readBytes(response.body(), UpstreamResponse.class);
            if (parsed == null || parsed.paymentId() == null || parsed.paymentId().isBlank()) {
                Map<String, Object> d = baseDetails(idempotencyKey, baseUrl, Map.of(
                        "httpStatus", status,
                        "bodyPreview", preview(response.body())));
                throw new IntegrationException(ErrorCode.UPSTREAM_PARSE_ERROR,
                        "上游响应体无法解析为预期结构", null, d, false);
            }
            String resultStatus = (parsed.status() == null || parsed.status().isBlank())
                    ? "ACCEPTED" : parsed.status();
            return new PaymentResult(parsed.paymentId(), resultStatus, request.amount(),
                    idempotencyKey, false, Instant.now());
        }

        // 非 2xx：408/429/5xx 可重试，其余（4xx）不可重试，避免对已被上游判定的错误重复生效
        boolean retryable = status == 408 || status == 429 || status >= 500;
        Map<String, Object> d = baseDetails(idempotencyKey, baseUrl, Map.of(
                "httpStatus", status,
                "retryable", retryable,
                "bodyPreview", preview(response.body()),
                "elapsedMs", System.currentTimeMillis() - started));
        throw new IntegrationException(ErrorCode.UPSTREAM_ERROR,
                "上游返回错误状态码 " + status, null, d, retryable);
    }

    private String classifyIo(IOException e) {
        String name = e.getClass().getSimpleName();
        return name == null || name.isBlank() ? "IOException" : name;
    }

    private Map<String, Object> baseDetails(String idempotencyKey, String baseUrl,
                                            Map<String, Object> extra) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("upstream", safeHost(baseUrl));
        d.put("idempotencyKey", maskKey(idempotencyKey));
        d.putAll(extra);
        return d;
    }

    private String safeHost(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 幂等键只保留前 4 位用于排查，避免完整业务标识外泄。 */
    private String maskKey(String key) {
        if (key == null) {
            return null;
        }
        if (key.length() <= 4) {
            return "****";
        }
        return key.substring(0, 4) + "****";
    }

    private String preview(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body);
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
