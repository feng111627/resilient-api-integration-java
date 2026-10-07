package com.github.feng111627.resilientapiintegrationjava.tests;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 测试用：调用我方 /api/payments 的最小 HTTP 客户端。 */
public class ApiClient {

    public record Response(int status, String body) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public Response postPayment(String baseUrl, String idempotencyKey, String jsonBody) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/payments"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            HttpResponse<byte[]> resp = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return new Response(resp.statusCode(),
                    new String(resp.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
