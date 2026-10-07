package com.github.feng111627.resilientapiintegrationjava.resilience.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调用层可配置项，前缀 resilience。
 */
@ConfigurationProperties(prefix = "resilience")
public class ResilienceProperties {

    private Upstream upstream = new Upstream();
    private Retry retry = new Retry();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Idempotency idempotency = new Idempotency();

    public Upstream getUpstream() { return upstream; }
    public void setUpstream(Upstream upstream) { this.upstream = upstream; }
    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }
    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreaker circuitBreaker) { this.circuitBreaker = circuitBreaker; }
    public Idempotency getIdempotency() { return idempotency; }
    public void setIdempotency(Idempotency idempotency) { this.idempotency = idempotency; }

    public static class Upstream {
        private String baseUrl = "http://localhost:9000";
        private long connectTimeoutMs = 1000;
        private long requestTimeoutMs = 2000;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public long getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(long requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    }

    public static class Retry {
        private int maxAttempts = 3;
        private long initialBackoffMs = 100;
        private double backoffMultiplier = 2.0;
        private long maxBackoffMs = 1000;
        /** 0.0~1.0，随机抖动比例。 */
        private double jitterRatio = 0.2;
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
        public long getMaxBackoffMs() { return maxBackoffMs; }
        public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }
        public double getJitterRatio() { return jitterRatio; }
        public void setJitterRatio(double jitterRatio) { this.jitterRatio = jitterRatio; }
    }

    public static class CircuitBreaker {
        private int failureThreshold = 5;
        private long openStateDurationMs = 10_000;
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public long getOpenStateDurationMs() { return openStateDurationMs; }
        public void setOpenStateDurationMs(long openStateDurationMs) { this.openStateDurationMs = openStateDurationMs; }
    }

    public static class Idempotency {
        private long ttlMillis = 24 * 60 * 60 * 1000;
        private int maxEntries = 10_000;
        /** 并发重复提交等待首个请求终态的最长时间。 */
        private long waitTimeoutMillis = 10_000;
        public long getTtlMillis() { return ttlMillis; }
        public void setTtlMillis(long ttlMillis) { this.ttlMillis = ttlMillis; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
        public long getWaitTimeoutMillis() { return waitTimeoutMillis; }
        public void setWaitTimeoutMillis(long waitTimeoutMillis) { this.waitTimeoutMillis = waitTimeoutMillis; }
    }
}
