package com.github.feng111627.resilientapiintegrationjava.upstream;

/** 一次上游调用的原始响应。 */
public record UpstreamResponse(int statusCode, String body) {
}
