package com.github.feng111627.resilientapiintegrationjava.upstream;

import java.util.Map;

/** 一次上游调用的请求描述。 */
public record UpstreamRequest(String path, String body, Map<String, String> headers) {
}
