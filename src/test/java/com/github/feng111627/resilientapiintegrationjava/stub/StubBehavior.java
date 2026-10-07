package com.github.feng111627.resilientapiintegrationjava.stub;

/** 桩服务单次/持续行为。 */
public enum StubBehavior {
    /** 正常 200 + JSON。 */
    OK,
    /** 读取请求后挂起，直到超过客户端超时。 */
    DELAY_FOREVER,
    /** 返回 500。 */
    FAIL_500,
    /** 返回非法 JSON（缺 paymentId 或直接坏格式）。 */
    MALFORMED_JSON,
    /** 返回 422 业务错误（不可重试）。 */
    REJECT_422,
    /** 返回 429（可重试）。 */
    FAIL_429
}
