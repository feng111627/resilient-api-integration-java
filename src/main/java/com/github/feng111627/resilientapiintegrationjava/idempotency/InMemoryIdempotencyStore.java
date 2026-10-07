package com.github.feng111627.resilientapiintegrationjava.idempotency;

import com.github.feng111627.resilientapiintegrationjava.model.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 {@link ConcurrentHashMap} 的内存幂等存储。
 *
 * <p>并发控制：{@code putIfAbsent} 保证同一时刻只有一个首个请求；
 * 重复提交在条目对象上 wait，终态写入后 notifyAll 唤醒。</p>
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    private static final class Entry {
        final String requestHash;
        final long createdAt;
        volatile IdempotencyState state = IdempotencyState.IN_FLIGHT;
        volatile PaymentResult result;
        volatile String errorCode;
        volatile long completedAt;

        Entry(String requestHash, long createdAt) {
            this.requestHash = requestHash;
            this.createdAt = createdAt;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;
    private final long waitTimeoutMillis;
    private final Clock clock;
    private final AtomicLong operationCounter = new AtomicLong();

    public InMemoryIdempotencyStore(long ttlMillis, int maxEntries, long waitTimeoutMillis) {
        this(ttlMillis, maxEntries, waitTimeoutMillis, Clock.systemUTC());
    }

    public InMemoryIdempotencyStore(long ttlMillis, int maxEntries, long waitTimeoutMillis,
                                    Clock clock) {
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
        this.waitTimeoutMillis = waitTimeoutMillis;
        this.clock = clock;
    }

    @Override
    public AcquireResult acquire(String key, String requestHash) {
        maybeEvict();
        Entry candidate = new Entry(requestHash, clock.millis());
        Entry existing = entries.putIfAbsent(key, candidate);
        if (existing == null) {
            return new AcquireResult.First();
        }
        synchronized (existing) {
            if (existing.state == IdempotencyState.ABORTED) {
                // 前一个占位在发起上游调用前被撤销：原子替换为新占位
                if (entries.replace(key, existing, candidate)) {
                    return new AcquireResult.First();
                }
                // 已被其他等待者替换，递归一次走正常分支
                return acquire(key, requestHash);
            }
            if (!requestHash.equals(existing.requestHash)) {
                log.warn("幂等键内容冲突 key={}", key);
                return new AcquireResult.Conflict(existing.requestHash, requestHash);
            }
            return switch (existing.state) {
                case IN_FLIGHT -> new AcquireResult.InFlight();
                case SUCCEEDED -> new AcquireResult.Completed(existing.result);
                case FAILED -> new AcquireResult.Failed(existing.errorCode);
                case ABORTED -> new AcquireResult.InFlight();
            };
        }
    }

    @Override
    public Optional<IdempotencyRecord> waitForCompletion(String key) throws InterruptedException {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        long deadline = clock.millis() + waitTimeoutMillis;
        synchronized (entry) {
            while (entry.state == IdempotencyState.IN_FLIGHT) {
                long remaining = deadline - clock.millis();
                if (remaining <= 0) {
                    return Optional.empty();
                }
                entry.wait(remaining);
            }
            return Optional.of(toRecord(key, entry));
        }
    }

    @Override
    public void complete(String key, PaymentResult result) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            entry.state = IdempotencyState.SUCCEEDED;
            entry.result = result;
            entry.completedAt = clock.millis();
            entry.notifyAll();
        }
        log.info("幂等请求完成 key={} paymentId={}", key, result.paymentId());
    }

    @Override
    public void fail(String key, String errorCode) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            entry.state = IdempotencyState.FAILED;
            entry.errorCode = errorCode;
            entry.completedAt = clock.millis();
            entry.notifyAll();
        }
        log.info("幂等请求标记失败 key={} errorCode={}", key, errorCode);
    }

    @Override
    public void abort(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            if (entry.state == IdempotencyState.IN_FLIGHT) {
                entry.state = IdempotencyState.ABORTED;
                entry.errorCode = "CIRCUIT_OPEN";
                entry.notifyAll();
                log.debug("撤销幂等占位 key={}", key);
            }
        }
    }

    @Override
    public Optional<PaymentResult> getCompleted(String key) {
        Entry entry = entries.get(key);
        if (entry != null && entry.state == IdempotencyState.SUCCEEDED) {
            return Optional.ofNullable(entry.result);
        }
        return Optional.empty();
    }

    private IdempotencyRecord toRecord(String key, Entry entry) {
        return new IdempotencyRecord(
                key,
                entry.requestHash,
                entry.state,
                entry.result,
                entry.errorCode,
                Instant.ofEpochMilli(entry.createdAt),
                entry.completedAt > 0 ? Instant.ofEpochMilli(entry.completedAt) : null);
    }

    /** 低频触发：先按 TTL 清理，再按容量清理最老条目，避免无界增长。 */
    private void maybeEvict() {
        if (operationCounter.incrementAndGet() % 100 != 0 && entries.size() <= maxEntries) {
            return;
        }
        long now = clock.millis();
        // TTL 淘汰
        entries.entrySet().removeIf(e -> {
            Entry en = e.getValue();
            long reference = en.completedAt > 0 ? en.completedAt : en.createdAt;
            return en.state != IdempotencyState.IN_FLIGHT && now - reference > ttlMillis;
        });
        // 容量淘汰：移除最老的非进行中条目
        while (entries.size() > maxEntries) {
            Map.Entry<String, Entry> oldest = entries.entrySet().stream()
                    .filter(e -> e.getValue().state != IdempotencyState.IN_FLIGHT)
                    .min(Comparator.comparingLong(e -> e.getValue().createdAt))
                    .orElse(null);
            if (oldest == null) {
                break;
            }
            entries.remove(oldest.getKey(), oldest.getValue());
        }
    }
}
