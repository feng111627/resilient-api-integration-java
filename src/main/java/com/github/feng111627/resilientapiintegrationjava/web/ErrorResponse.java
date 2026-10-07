package com.github.feng111627.resilientapiintegrationjava.web;

/** 对外统一错误响应体。 */
public record ErrorResponse(String error, String message, String requestId) {
}
