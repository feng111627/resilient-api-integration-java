package com.github.feng111627.resilientapiintegrationjava.model;

/**
 * 统一错误分类。对外错误响应只暴露该分类，不暴露底层 HTTP / 序列化库异常。
 */
public enum ErrorCode {
    /** 请求参数不合法（400）。 */
    VALIDATION_ERROR,
    /** 缺少幂等键（400）。 */
    MISSING_IDEMPOTENCY_KEY,
    /** 幂等键被复用但请求内容不一致（409），与普通参数错误区分。 */
    IDEMPOTENCY_CONFLICT,
    /** 同键请求仍在处理中，重复提交等待超时（409，提示稍后再试）。 */
    IDEMPOTENCY_IN_FLIGHT,
    /** 同键首个请求已失败，重放其失败结果；如需重试请使用新幂等键（409）。 */
    IDEMPOTENCY_PREVIOUS_FAILED,
    /** 调用上游超时（504）。 */
    UPSTREAM_TIMEOUT,
    /** 上游返回错误状态码（502）。 */
    UPSTREAM_ERROR,
    /** 上游响应体解析失败（502）。 */
    UPSTREAM_PARSE_ERROR,
    /** 熔断打开期间拒绝调用（503）。 */
    CIRCUIT_OPEN,
    /** 重试耗尽后上游仍不可用（503）。 */
    UPSTREAM_UNAVAILABLE,
    /** 兜底内部错误（500）。 */
    INTERNAL_ERROR
}
