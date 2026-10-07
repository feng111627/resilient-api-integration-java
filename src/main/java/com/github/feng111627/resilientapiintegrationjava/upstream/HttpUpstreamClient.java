package com.github.feng111627.resilientapiintegrationjava.upstream;

import com.github.feng111627.resilientapiintegrationjava.error.ErrorKind;
import com.github.feng111627.resilientapiintegrationjava.error.UpstreamException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;

/**
 * 基于 JDK HttpClient 的上游调用实现。
 * 负责把底层异常归类为统一异常：超时 -> TIMEOUT，错误状态码 -> UPSTREAM_ERROR，
 * 其它 IO 异常 -> UPSTREAM_ERROR（不带状态码），不向调用方透出 HttpClient 异常类型。
 */
public class HttpUpstreamClient implements UpstreamClient {

    private final String baseUrl;
    private final long readTimeoutMs;
    private final HttpClient httpClient;

    public HttpUpstreamClient(String baseUrl, long connectTimeoutMs, long readTimeoutMs) {
        this.baseUrl = baseUrl;
        this.readTimeoutMs = readTimeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    @Override
    public UpstreamResponse post(UpstreamRequest request) {
        String requestId = UUID.randomUUID().toString();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + request.path()))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(
                        request.body() == null ? "" : request.body()));
        request.headers().forEach(builder::header);

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new UpstreamException(ErrorKind.TIMEOUT,
                    "上游调用超时（" + readTimeoutMs + "ms）", null, requestId, e);
        } catch (IOException e) {
            throw new UpstreamException(ErrorKind.UPSTREAM_ERROR,
                    "上游连接失败: " + e.getMessage(), null, requestId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(ErrorKind.UPSTREAM_ERROR,
                    "上游调用被中断", null, requestId, e);
        }

        int status = response.statusCode();
        if (status >= 400) {
            throw new UpstreamException(ErrorKind.UPSTREAM_ERROR,
                    "上游返回错误状态 " + status, status, requestId, null);
        }
        return new UpstreamResponse(status, response.body());
    }
}
