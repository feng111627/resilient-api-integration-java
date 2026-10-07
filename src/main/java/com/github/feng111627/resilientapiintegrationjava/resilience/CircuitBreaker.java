package com.github.feng111627.resilientapiintegrationjava.resilience;

import java.time.Clock;

/**
 * 针对上游的熔断器。
 *
 * <ul>
 *   <li>CLOSED：正常放行，连续失败计数；达到 failureThreshold 后转到 OPEN。</li>
 *   <li>OPEN：在 openStateDurationMs 冷却期内快速失败；冷却结束后转到 HALF_OPEN。</li>
 *   <li>HALF_OPEN：只放行一个试探请求；成功 -> CLOSED，失败 -> 重新 OPEN 并重新计时。</li>
 * </ul>
 *
 * 熔断器只记录“调用结果”，不缓存业务结果；OPEN 期间拒绝不会影响此前已完成的幂等结果。
 */
public class CircuitBreaker {

    private final int failureThreshold;
    private final long openStateDurationMs;
    private final Clock clock;

    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtMillis = 0L;
    private boolean halfOpenInFlight = false;

    public CircuitBreaker(int failureThreshold, long openStateDurationMs) {
        this(failureThreshold, openStateDurationMs, Clock.systemUTC());
    }

    public CircuitBreaker(int failureThreshold, long openStateDurationMs, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (openStateDurationMs < 0) {
            throw new IllegalArgumentException("openStateDurationMs must be >= 0");
        }
        this.failureThreshold = failureThreshold;
        this.openStateDurationMs = openStateDurationMs;
        this.clock = clock;
    }

    private long nowMs() {
        return clock.millis();
    }

    /**
     * 是否允许一次调用。OPEN 且冷却未到 -> false；
     * 冷却到 -> 转 HALF_OPEN 并放行唯一一个试探请求。
     */
    public synchronized boolean allowRequest() {
        if (state == CircuitState.CLOSED) {
            return true;
        }
        if (state == CircuitState.OPEN) {
            if (nowMs() - openedAtMillis >= openStateDurationMs) {
                state = CircuitState.HALF_OPEN;
                halfOpenInFlight = true;
                return true;
            }
            return false;
        }
        // HALF_OPEN：同一时刻只允许一个试探请求
        if (halfOpenInFlight) {
            return false;
        }
        halfOpenInFlight = true;
        return true;
    }

    /** 记录一次成功：HALF_OPEN 试探成功则恢复 CLOSED；CLOSED 下清零计数。 */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        halfOpenInFlight = false;
        state = CircuitState.CLOSED;
        openedAtMillis = 0L;
    }

    /** 记录一次失败：CLOSED 累加计数，达到阈值转 OPEN；HALF_OPEN 试探失败立即重新 OPEN。 */
    public synchronized void recordFailure() {
        if (state == CircuitState.HALF_OPEN) {
            tripOpen();
            return;
        }
        if (state == CircuitState.CLOSED) {
            consecutiveFailures++;
            if (consecutiveFailures >= failureThreshold) {
                tripOpen();
            }
        }
        // OPEN 状态下的失败无需处理（allowRequest 已拒绝，正常不会记录到）
    }

    private void tripOpen() {
        state = CircuitState.OPEN;
        openedAtMillis = nowMs();
        halfOpenInFlight = false;
    }

    public synchronized CircuitState currentState() {
        // 顺带做一次基于时间的状态推进，便于观测
        if (state == CircuitState.OPEN && nowMs() - openedAtMillis >= openStateDurationMs) {
            return CircuitState.HALF_OPEN;
        }
        return state;
    }

    /** OPEN 状态剩余冷却毫秒；已到冷却时间或非 OPEN 返回 0。 */
    public synchronized long remainingOpenMillis() {
        if (state != CircuitState.OPEN) {
            return 0L;
        }
        long elapsed = nowMs() - openedAtMillis;
        return Math.max(0L, openStateDurationMs - elapsed);
    }

    /** 重置为 CLOSED 并清零计数（主要用于测试隔离）。 */
    public synchronized void reset() {
        state = CircuitState.CLOSED;
        consecutiveFailures = 0;
        openedAtMillis = 0L;
        halfOpenInFlight = false;
    }
}
