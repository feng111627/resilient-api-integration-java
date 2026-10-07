package com.github.feng111627.resilientapiintegrationjava.idempotency;

import com.github.feng111627.resilientapiintegrationjava.error.ErrorKind;
import com.github.feng111627.resilientapiintegrationjava.error.UpstreamException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内存幂等存储：
 * - 同键同内容并发提交：只有第一个线程真正执行（单飞），其余等待并共享同一结果，
 *   上游副作用只发生一次；
 * - 同键同内容完成后重复提交：直接返回缓存结果，不再调用上游；
 * - 同键不同内容：抛出 IDEMPOTENCY_CONFLICT（与参数校验错误区分开）；
 * - 执行失败：清除占位记录，后续同键请求可正常重试，不会被卡死。
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    private record Entry(String fingerprint, CompletableFuture<Object> result) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(String key, String fingerprint, Supplier<T> action) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        Entry existing = entries.putIfAbsent(key, new Entry(fingerprint, future));

        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new UpstreamException(ErrorKind.IDEMPOTENCY_CONFLICT,
                        "幂等键已被不同内容的请求使用: " + key);
            }
            // 同键同内容：等待首个执行者的结果（已完成则立即返回）
            log.info("[idempotency] 键 {} 命中已有记录，复用结果", key);
            try {
                return (T) existing.result().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException(ErrorKind.UPSTREAM_ERROR, "等待幂等结果被中断",
                        null, null, e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new UpstreamException(ErrorKind.UPSTREAM_ERROR, "幂等执行失败",
                        null, null, cause);
            }
        }

        // 当前线程负责真正执行
        try {
            T result = action.get();
            future.complete(result);
            return result;
        } catch (RuntimeException e) {
            // 失败不留痕：移除占位，允许后续同键请求重试
            entries.remove(key);
            future.completeExceptionally(e);
            throw e;
        }
    }
}
