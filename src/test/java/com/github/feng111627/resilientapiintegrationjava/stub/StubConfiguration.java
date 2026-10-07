package com.github.feng111627.resilientapiintegrationjava.stub;

import com.github.feng111627.resilientapiintegrationjava.client.UpstreamBaseUrlProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 桩服务装配：同时把上游地址覆盖为本机随机端口。 */
@TestConfiguration
public class StubConfiguration {

    @Bean
    public StubScenarioRegistry stubScenarioRegistry() {
        return new StubScenarioRegistry();
    }

    @Bean
    public UpstreamStubController upstreamStubController(StubScenarioRegistry registry) {
        return new UpstreamStubController(registry);
    }

    @Bean
    @Primary
    public UpstreamBaseUrlProvider localhostUpstreamBaseUrlProvider(ApplicationContext context) {
        return new LocalhostUpstreamBaseUrlProvider(context);
    }
}
