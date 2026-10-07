package com.github.feng111627.resilientapiintegrationjava.tests;

import com.github.feng111627.resilientapiintegrationjava.Application;
import com.github.feng111627.resilientapiintegrationjava.resilience.CircuitBreaker;
import com.github.feng111627.resilientapiintegrationjava.stub.StubConfiguration;
import com.github.feng111627.resilientapiintegrationjava.stub.StubScenarioRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * 测试基类：在同一进程内启动“我方服务 + 本地桩上游”，全部走 localhost，随机端口。
 * 超时/退避/熔断阈值被调小，保证用例快速完成。
 */
@SpringBootTest(
        classes = {Application.class, StubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "resilience.upstream.connect-timeout-ms=300",
        "resilience.upstream.request-timeout-ms=300",
        "resilience.retry.max-attempts=3",
        "resilience.retry.initial-backoff-ms=20",
        "resilience.retry.backoff-multiplier=2.0",
        "resilience.retry.max-backoff-ms=80",
        "resilience.retry.jitter-ratio=0.0",
        "resilience.circuit-breaker.failure-threshold=5",
        "resilience.circuit-breaker.open-state-duration-ms=600",
        "resilience.idempotency.wait-timeout-ms=3000"
})
public abstract class AbstractIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected StubScenarioRegistry registry;

    @Autowired
    protected CircuitBreaker circuitBreaker;

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** 把上游基地址指向本进程的桩（@TestPropertySource 中无法用表达式，运行期覆盖 bean）。 */
    /** 每个用例前重置桩规则与熔断器，保证相互独立。 */
    @BeforeEach
    void resetState() {
        registry.reset();
        circuitBreaker.reset();
    }
}
