package com.github.feng111627.resilientapiintegrationjava.upstream;

/** 上游 HTTP 调用的抽象，便于用本地桩替换。 */
public interface UpstreamClient {

    /**
     * 执行一次调用。
     *
     * @throws com.github.feng111627.resilientapiintegrationjava.error.UpstreamException
     *         超时或上游错误状态等，已归类为统一异常
     */
    UpstreamResponse post(UpstreamRequest request);
}
