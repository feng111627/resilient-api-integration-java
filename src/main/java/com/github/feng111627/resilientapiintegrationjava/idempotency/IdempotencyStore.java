package com.github.feng111627.resilientapiintegrationjava.idempotency;

import com.github.feng111627.resilientapiintegrationjava.model.PaymentResult;

import java.util.Optional;

/**
 * 幂等键存储：并发去重、内容指纹冲突检测、已完成结果缓存。
 *
 * <p>语义约定：</p>
 * <ul>
 *   <li>同一幂等键 + 相同请求指纹：首个请求执行；进行中并发重复提交等待其终态后重放；
 *       已完成则直接重放成功结果；已失败则重放失败信息（不允许接手上次调用，避免重复副作用）。</li>
 *   <li>同一幂等键 + 不同请求指纹：立即冲突拒绝。</li>
 * </ul>
 */
public interface IdempotencyStore {

    /** acquire 的结果。 */
    sealed interface AcquireResult {
        /** 当前调用是首个持有者，需要真正执行上游调用。 */
        record First() implements AcquireResult {}
        /** 同键请求进行中，调用方应等待终态。 */
        record InFlight() implements AcquireResult {}
        /** 同键请求已成功，直接重放结果。 */
        record Completed(PaymentResult result) implements AcquireResult {}
        /** 同键请求已失败，重放失败信息（errorCode 为稳定分类码）。 */
        record Failed(String errorCode) implements AcquireResult {}
        /** 键被不同请求内容复用，必须拒绝并与普通参数错误区分。 */
        record Conflict(String existingHash, String incomingHash) implements AcquireResult {}
    }

    /**
     * 尝试占用一个幂等键。该操作必须是原子的。
     */
    AcquireResult acquire(String key, String requestHash);

    /** 等待同键进行中的首个请求完成，并返回其终态记录。 */
    Optional<IdempotencyRecord> waitForCompletion(String key) throws InterruptedException;

    /** 首个请求成功后提交结果，唤醒所有等待者。 */
    void complete(String key, PaymentResult result);

    /** 首个请求失败后记录失败（失败记录保留至 TTL，防止并发/重复提交触发第二次副作用）。 */
    void fail(String key, String errorCode);

    /**
     * 撤销仍处于 IN_FLIGHT 的占位（如尚未发起上游调用即被熔断拒绝）。
     * 条目被移除并唤醒等待者；已是终态的记录不受影响。
     */
    void abort(String key);

    /** 读取已完成的成功结果。 */
    Optional<PaymentResult> getCompleted(String key);
}
