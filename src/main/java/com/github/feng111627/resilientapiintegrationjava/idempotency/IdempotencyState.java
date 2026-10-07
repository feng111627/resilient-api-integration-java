package com.github.feng111627.resilientapiintegrationjava.idempotency;

/** 幂等记录状态。 */
public enum IdempotencyState {
    IN_FLIGHT,
    SUCCEEDED,
    FAILED,
    /** 占位在真正发起上游调用前被撤销（如熔断 OPEN 拒绝），等待者应重新抢占。 */
    ABORTED
}
