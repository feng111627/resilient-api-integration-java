package com.github.feng111627.resilientapiintegrationjava.client;

import com.github.feng111627.resilientapiintegrationjava.resilience.config.ResilienceProperties;

/** 基于配置属性的默认上游地址提供者。 */
public class ConfiguredUpstreamBaseUrlProvider implements UpstreamBaseUrlProvider {

    private final ResilienceProperties properties;

    public ConfiguredUpstreamBaseUrlProvider(ResilienceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String get() {
        return properties.getUpstream().getBaseUrl();
    }
}
