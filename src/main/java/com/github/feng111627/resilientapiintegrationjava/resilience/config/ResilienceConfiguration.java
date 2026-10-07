package com.github.feng111627.resilientapiintegrationjava.resilience.config;

import com.github.feng111627.resilientapiintegrationjava.client.ConfiguredUpstreamBaseUrlProvider;
import com.github.feng111627.resilientapiintegrationjava.client.UpstreamBaseUrlProvider;
import com.github.feng111627.resilientapiintegrationjava.client.UpstreamPaymentClient;
import com.github.feng111627.resilientapiintegrationjava.client.json.JsonCodec;
import com.github.feng111627.resilientapiintegrationjava.idempotency.IdempotencyStore;
import com.github.feng111627.resilientapiintegrationjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.resilientapiintegrationjava.resilience.CircuitBreaker;
import com.github.feng111627.resilientapiintegrationjava.resilience.RetryExecutor;
import com.github.feng111627.resilientapiintegrationjava.resilience.Sleeper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/** 容错层、幂等存储与上游客户端的 Spring 装配。 */
@Configuration
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceConfiguration {

    @Bean
    public JsonCodec jsonCodec() {
        return new JsonCodec();
    }

    @Bean
    public HttpClient httpClient(ResilienceProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getUpstream().getConnectTimeoutMs()))
                .build();
    }

    @Bean
    public CircuitBreaker circuitBreaker(ResilienceProperties props) {
        ResilienceProperties.CircuitBreaker cb = props.getCircuitBreaker();
        return new CircuitBreaker(cb.getFailureThreshold(), cb.getOpenStateDurationMs());
    }

    @Bean
    public RetryExecutor retryExecutor(ResilienceProperties props, Sleeper sleeper) {
        ResilienceProperties.Retry r = props.getRetry();
        return new RetryExecutor(r.getMaxAttempts(), r.getInitialBackoffMs(),
                r.getBackoffMultiplier(), r.getMaxBackoffMs(), r.getJitterRatio(), sleeper);
    }

    @Bean
    public UpstreamBaseUrlProvider upstreamBaseUrlProvider(ResilienceProperties props) {
        return new ConfiguredUpstreamBaseUrlProvider(props);
    }

    @Bean
    public UpstreamPaymentClient upstreamPaymentClient(HttpClient httpClient,
                                                       UpstreamBaseUrlProvider baseUrlProvider,
                                                       ResilienceProperties props,
                                                       JsonCodec jsonCodec) {
        return new UpstreamPaymentClient(httpClient, baseUrlProvider, props.getUpstream(), jsonCodec);
    }

    @Bean
    public IdempotencyStore idempotencyStore(ResilienceProperties props) {
        ResilienceProperties.Idempotency idem = props.getIdempotency();
        return new InMemoryIdempotencyStore(idem.getTtlMillis(), idem.getMaxEntries(),
                idem.getWaitTimeoutMillis());
    }
}
