package com.github.feng111627.resilientapiintegrationjava.client;

import java.util.function.Supplier;

/**
 * 上游基地址提供者。默认使用配置文件中的静态地址；
 * 测试中可提供基于随机端口的动态地址。
 */
public interface UpstreamBaseUrlProvider extends Supplier<String> {
}
