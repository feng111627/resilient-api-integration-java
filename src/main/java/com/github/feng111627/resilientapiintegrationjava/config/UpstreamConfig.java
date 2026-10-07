package com.github.feng111627.resilientapiintegrationjava.config;

import com.github.feng111627.resilientapiintegrationjava.idempotency.IdempotencyStore;
import com.github.feng111627.resilientapiintegrationjava.idempotency.InMemoryIdempotencyStore;
import com.github.feng111627.resilientapiintegrationjava.payment.PaymentService;
import com.github.feng111627.resilientapiintegrationjava.resilience.CircuitBreaker;
import com.github.feng111627.resilientapiintegrationjava.resilience.RetryPolicy;
import com.github.feng111627.resilientapiintegrationjava.upstream.HttpUpstreamClient;
import com.github.feng111627.resilientapiintegrationjava.upstream.UpstreamClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 调用层 Bean 装配。 */
@Configuration
@EnableConfigurationProperties(ResilienceProperties.class)
public class UpstreamConfig {

    @Bean
    public UpstreamClient upstreamClient(ResilienceProperties props) {
        return new HttpUpstreamClient(props.getUpstreamBaseUrl(),
                props.getConnectTimeoutMs(), props.getReadTimeoutMs());
    }

    @Bean
    public IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    public RetryPolicy retryPolicy(ResilienceProperties props) {
        return new RetryPolicy(props.getMaxAttempts(),
                props.getInitialBackoffMs(), props.getBackoffMultiplier());
    }

    @Bean
    public CircuitBreaker circuitBreaker(ResilienceProperties props) {
        return new CircuitBreaker(props.getFailureThreshold(), props.getCooldownMs());
    }

    @Bean
    public PaymentService paymentService(UpstreamClient upstreamClient,
                                         IdempotencyStore idempotencyStore,
                                         RetryPolicy retryPolicy,
                                         CircuitBreaker circuitBreaker) {
        return new PaymentService(upstreamClient, idempotencyStore, retryPolicy, circuitBreaker);
    }
}
