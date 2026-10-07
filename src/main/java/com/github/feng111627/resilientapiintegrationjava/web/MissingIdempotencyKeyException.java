package com.github.feng111627.resilientapiintegrationjava.web;

/** 缺少幂等键请求头时抛出，与普通参数错误分类不同。 */
public class MissingIdempotencyKeyException extends RuntimeException {
    public MissingIdempotencyKeyException() {
        super("缺少必填请求头 Idempotency-Key");
    }
}
