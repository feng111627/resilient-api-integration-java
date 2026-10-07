package com.github.feng111627.resilientapiintegrationjava.resilience;

import com.github.feng111627.resilientapiintegrationjava.exception.IntegrationException;
import com.github.feng111627.resilientapiintegrationjava.model.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 有限次数指数退避重试器。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>总共最多执行 maxAttempts 次（首次 + 后续重试）。</li>
 *   <li>只有 {@link IntegrationException#isRetryable()} 为 true 的失败才会重试；
 *       参数错误、4xx、熔断拒绝、解析失败等不可安全重试的错误立即外抛。</li>
 *   <li>退避 = min(maxBackoff, initialBackoff * multiplier^n)，再乘以 [1-j, 1+j] 的抖动系数。</li>
 *   <li>等待期间被中断：恢复中断标志并立即以非重试异常失败，不再产生重复调用。</li>
 * </ul>
 */
public class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final double multiplier;
    private final long maxBackoffMs;
    private final double jitterRatio;
    private final Sleeper sleeper;
    private final Random random;

    public RetryExecutor(int maxAttempts, long initialBackoffMs, double multiplier,
                         long maxBackoffMs, double jitterRatio, Sleeper sleeper) {
        this(maxAttempts, initialBackoffMs, multiplier, maxBackoffMs, jitterRatio,
                sleeper, new Random());
    }

    RetryExecutor(int maxAttempts, long initialBackoffMs, double multiplier,
                  long maxBackoffMs, double jitterRatio, Sleeper sleeper, Random random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.multiplier = multiplier;
        this.maxBackoffMs = maxBackoffMs;
        this.jitterRatio = jitterRatio;
        this.sleeper = sleeper;
        this.random = random;
    }

    public <T> T executeWithRetry(Supplier<T> action) throws IntegrationException {
        IntegrationException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = action.get();
                if (attempt > 1) {
                    log.info("上游调用在第 {}/{} 次尝试后成功", attempt, maxAttempts);
                }
                return result;
            } catch (IntegrationException e) {
                lastFailure = e;
                if (!e.isRetryable() || attempt == maxAttempts) {
                    Map<String, Object> d = new LinkedHashMap<>(e.getDetails());
                    d.put("attempt", attempt);
                    d.put("maxAttempts", maxAttempts);
                    if (!e.isRetryable()) {
                        // 不可重试：原样抛出，保留原始错误分类与现场
                        throw e;
                    }
                    log.warn("上游调用第 {}/{} 次失败（code={}，可重试），重试次数已耗尽",
                            attempt, maxAttempts, e.getErrorCode());
                    throw new IntegrationException(ErrorCode.UPSTREAM_UNAVAILABLE,
                            "上游在 " + attempt + " 次尝试后仍不可用: " + e.getMessage(),
                            e.getCause(), d, false);
                }
                long waitMs = nextBackoffMillis(attempt - 1);
                log.warn("上游调用第 {}/{} 次失败（code={}），{}ms 后重试",
                        attempt, maxAttempts, e.getErrorCode(), waitMs);
                try {
                    sleeper.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    Map<String, Object> d = new LinkedHashMap<>(e.getDetails());
                    d.put("attempt", attempt);
                    d.put("interruptedWhileBackingOff", true);
                    throw new IntegrationException(ErrorCode.UPSTREAM_UNAVAILABLE,
                            "等待重试期间被中断，已停止后续调用", ie, d, false);
                }
            }
        }
        // 理论不可达
        throw lastFailure;
    }

    private long nextBackoffMillis(int retryIndex) {
        long base = (long) Math.min(maxBackoffMs,
                initialBackoffMs * Math.pow(multiplier, retryIndex));
        if (jitterRatio <= 0) {
            return base;
        }
        double factor = 1.0 - jitterRatio + (2.0 * jitterRatio * random.nextDouble());
        return Math.max(0L, Math.min(maxBackoffMs, Math.round(base * factor)));
    }
}
