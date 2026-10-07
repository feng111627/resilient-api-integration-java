package com.github.feng111627.resilientapiintegrationjava.error;

/** 对外可区分的错误分类。 */
public enum ErrorKind {
    /** 上游调用超时 */
    TIMEOUT,
    /** 上游返回了错误状态码 */
    UPSTREAM_ERROR,
    /** 上游响应体解析失败 */
    PARSE_ERROR,
    /** 熔断期间被快速拒绝 */
    CIRCUIT_OPEN,
    /** 幂等键被复用但请求内容不同 */
    IDEMPOTENCY_CONFLICT
}
