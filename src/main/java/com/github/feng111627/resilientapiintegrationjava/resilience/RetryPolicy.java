package com.github.feng111627.resilientapiintegrationjava.resilience;

import com.github.feng111627.resilientapiintegrationjava.error.UpstreamException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 有限次数的指数退避重试。
 * 只重试可安全重试的失败：超时、5xx、429（配合幂等键，上游可去重，不会重复生效）。
 * 不重试：解析失败、熔断拒绝、幂等冲突、4xx（除 429）。
 */
public class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final double backoffMultiplier;

    public RetryPolicy(int maxAttempts, long initialBackoffMs, double backoffMultiplier) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.backoffMultiplier = backoffMultiplier;
    }

    public boolean isRetryable(UpstreamException e) {
        return switch (e.kind()) {
            case TIMEOUT -> true;
            case UPSTREAM_ERROR -> e.upstreamStatus() == null
                    || e.upstreamStatus() >= 500
                    || e.upstreamStatus() == 429;
            default -> false;
        };
    }

    public <T> T execute(Supplier<T> action) {
        UpstreamException last = null;
        long backoff = initialBackoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (UpstreamException e) {
                last = e;
                if (!isRetryable(e) || attempt == maxAttempts) {
                    throw e;
                }
                log.info("[retry] 第 {}/{} 次尝试失败（{}），{}ms 后重试, requestId={}",
                        attempt, maxAttempts, e.kind(), backoff, e.requestId());
                sleep(backoff);
                backoff = (long) (backoff * backoffMultiplier);
            }
        }
        throw last;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
