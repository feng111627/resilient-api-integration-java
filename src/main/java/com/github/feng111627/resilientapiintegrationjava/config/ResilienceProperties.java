package com.github.feng111627.resilientapiintegrationjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 调用层可配置项。 */
@ConfigurationProperties(prefix = "resilience")
public class ResilienceProperties {

    private String upstreamBaseUrl = "http://localhost:9000";
    private long connectTimeoutMs = 1000;
    private long readTimeoutMs = 2000;
    private int maxAttempts = 3;
    private long initialBackoffMs = 100;
    private double backoffMultiplier = 2.0;
    private int failureThreshold = 3;
    private long cooldownMs = 5000;

    public String getUpstreamBaseUrl() { return upstreamBaseUrl; }
    public void setUpstreamBaseUrl(String upstreamBaseUrl) { this.upstreamBaseUrl = upstreamBaseUrl; }
    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public long getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getInitialBackoffMs() { return initialBackoffMs; }
    public void setInitialBackoffMs(long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
    public double getBackoffMultiplier() { return backoffMultiplier; }
    public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    public long getCooldownMs() { return cooldownMs; }
    public void setCooldownMs(long cooldownMs) { this.cooldownMs = cooldownMs; }
}
