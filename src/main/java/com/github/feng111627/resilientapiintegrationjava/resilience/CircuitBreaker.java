package com.github.feng111627.resilientapiintegrationjava.resilience;

import com.github.feng111627.resilientapiintegrationjava.error.ErrorKind;
import com.github.feng111627.resilientapiintegrationjava.error.UpstreamException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 熔断器（线程安全）：
 * CLOSED  —— 正常调用，连续失败达到阈值后切到 OPEN；
 * OPEN    —— 快速失败（CIRCUIT_OPEN），冷却期满后放行一个试探请求进入 HALF_OPEN；
 * HALF_OPEN —— 试探成功则关闭并重置计数，失败则重新打开并重新计时冷却。
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownMs;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAtMs;

    public CircuitBreaker(int failureThreshold, long cooldownMs) {
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    public synchronized State state() {
        return state;
    }

    public <T> T execute(Supplier<T> action) {
        beforeCall();
        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (RuntimeException e) {
            onFailure();
            throw e;
        }
    }

    private synchronized void beforeCall() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAtMs >= cooldownMs) {
                state = State.HALF_OPEN;
                log.info("[circuit] 冷却期满，进入 HALF_OPEN 试探");
            } else {
                throw new UpstreamException(ErrorKind.CIRCUIT_OPEN,
                        "熔断器打开，快速失败（冷却 " + cooldownMs + "ms）");
            }
        }
        // HALF_OPEN 状态下只放行当前这个试探请求；
        // 试探期间并发的其它请求按 OPEN 处理，避免故障上游被打爆。
        if (state == State.HALF_OPEN) {
            state = State.OPEN; // 占位，试探结果出来前其它线程快速失败
            openedAtMs = System.currentTimeMillis();
            probingThread = Thread.currentThread();
            return;
        }
    }

    private Thread probingThread;

    private synchronized void onSuccess() {
        if (state == State.OPEN && Thread.currentThread() == probingThread) {
            // 试探成功
            probingThread = null;
        }
        if (consecutiveFailures > 0 || state != State.CLOSED) {
            log.info("[circuit] 调用成功，熔断器关闭");
        }
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    private synchronized void onFailure() {
        if (Thread.currentThread() == probingThread) {
            probingThread = null;
            consecutiveFailures = failureThreshold;
            openedAtMs = System.currentTimeMillis();
            log.info("[circuit] 试探失败，保持 OPEN，重新冷却 {}ms", cooldownMs);
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold && state != State.OPEN) {
            state = State.OPEN;
            openedAtMs = System.currentTimeMillis();
            log.warn("[circuit] 连续失败 {} 次，熔断器打开，冷却 {}ms", consecutiveFailures, cooldownMs);
        }
    }
}
