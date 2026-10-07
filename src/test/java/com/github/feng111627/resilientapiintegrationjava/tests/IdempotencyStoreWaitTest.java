package com.github.feng111627.resilientapiintegrationjava.tests;

import com.github.feng111627.resilientapiintegrationjava.idempotency.IdempotencyStore;
import com.github.feng111627.resilientapiintegrationjava.idempotency.InMemoryIdempotencyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等存储单元测试：首个请求长期进行中时，重复提交等待超时后应得到空结果，
 * 由上层转换为可区分的 IDEMPOTENCY_IN_FLIGHT，而不是无限卡死。
 */
@DisplayName("幂等存储：进行中等待超时不被卡死")
class IdempotencyStoreWaitTest {

    @Test
    @DisplayName("IN_FLIGHT 超过等待时限：waitForCompletion 返回空")
    void waitTimesOut() throws InterruptedException {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(60_000, 100, 200);

        assertThat(store.acquire("k", "h"))
                .isInstanceOf(IdempotencyStore.AcquireResult.First.class);
        assertThat(store.acquire("k", "h"))
                .isInstanceOf(IdempotencyStore.AcquireResult.InFlight.class);

        long start = System.currentTimeMillis();
        Optional<?> outcome = store.waitForCompletion("k");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(outcome).isEmpty();
        assertThat(elapsed).isBetween(150L, 800L);
    }

    @Test
    @DisplayName("等待期间首个请求完成：能拿到终态结果")
    void waitReturnsWhenCompleted() throws InterruptedException {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(60_000, 100, 2000);
        String key = "k2";
        assertThat(store.acquire(key, "h"))
                .isInstanceOf(IdempotencyStore.AcquireResult.First.class);

        Thread completer = new Thread(() -> {
            try {
                Thread.sleep(150);
                store.complete(key, new com.github.feng111627.resilientapiintegrationjava
                        .model.PaymentResult("PAY-1", "ACCEPTED",
                        java.math.BigDecimal.ONE, key, false, java.time.Instant.now()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        completer.start();

        Optional<?> outcome = store.waitForCompletion(key);
        completer.join();

        assertThat(outcome).isPresent();
        assertThat(store.getCompleted(key)).isPresent();
    }
}
