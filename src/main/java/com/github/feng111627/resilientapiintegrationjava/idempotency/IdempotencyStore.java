package com.github.feng111627.resilientapiintegrationjava.idempotency;

import java.util.function.Supplier;

/** 幂等键存储：并发同键单飞、完成结果缓存、键复用冲突检测。 */
public interface IdempotencyStore {

    /**
     * 以幂等键执行业务动作。
     * 同键同内容：并发时只执行一次，其余等待同一结果；完成后直接返回缓存结果。
     * 同键不同内容：抛出 IDEMPOTENCY_CONFLICT 异常。
     */
    <T> T execute(String key, String fingerprint, Supplier<T> action);
}
