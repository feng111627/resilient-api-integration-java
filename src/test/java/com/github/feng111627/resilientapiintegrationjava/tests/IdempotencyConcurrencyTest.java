package com.github.feng111627.resilientapiintegrationjava.tests;

import com.github.feng111627.resilientapiintegrationjava.stub.StubBehavior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务：支付下单幂等。
 * 覆盖：并发同键重复提交只产生一次上游副作用、结果一致且为重放。
 */
@DisplayName("业务-支付下单：幂等与并发去重")
class IdempotencyConcurrencyTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConcurrencyTest.class);

    private static final String BODY =
            "{\"amount\":12.50,\"payerAccount\":\"A001\",\"payeeAccount\":\"B001\"}";

    @Test
    @DisplayName("同键并发 10 次提交：上游只生效一次，其余重放同一 paymentId")
    void concurrentSameKey_deduplicates() throws Exception {
        String key = "idem-concurrent-" + UUID.randomUUID();
        registry.setRule(key, StubBehavior.OK);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiClient.Response>> futures = new ArrayList<>();
        ApiClient client = new ApiClient();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return client.postPayment(baseUrl(), key, BODY);
            }));
        }
        ready.await();
        log.info(">>> 同时放行 {} 个相同幂等键的并发请求 key={}", threads, key);
        start.countDown();

        List<ApiClient.Response> responses = new ArrayList<>();
        for (Future<ApiClient.Response> f : futures) {
            responses.add(f.get());
        }
        pool.shutdown();

        assertThat(responses).hasSize(threads);
        List<String> paymentIds = responses.stream().map(r -> extract(r.body(), "paymentId")).toList();
        long created = responses.stream().filter(r -> r.status() == 201).count();
        long okReplay = responses.stream().filter(r -> r.status() == 200).count();

        log.info("结果：201 首次={}，200 重放={}，上游实际调用次数={}",
                created, okReplay, registry.callCountOf(key));

        assertThat(paymentIds).doesNotContainNull().allMatch(paymentIds.get(0)::equals);
        assertThat(registry.callCountOf(key)).as("上游只应被调用一次").isEqualTo(1);
        assertThat(created).as("恰好一个首次成功").isEqualTo(1);
        assertThat(okReplay).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("同键串行重复提交：第二次直接重放，不再调用上游")
    void sequentialSameKey_replays() {
        String key = "idem-seq-" + UUID.randomUUID();
        registry.setRule(key, StubBehavior.OK);
        ApiClient client = new ApiClient();

        ApiClient.Response first = client.postPayment(baseUrl(), key, BODY);
        ApiClient.Response second = client.postPayment(baseUrl(), key, BODY);

        log.info("串行重复提交：第一次 status={}，第二次 status={}，上游次数={}",
                first.status(), second.status(), registry.callCountOf(key));

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(extract(second.body(), "replayed")).isEqualTo("true");
        assertThat(extract(first.body(), "paymentId"))
                .isEqualTo(extract(second.body(), "paymentId"));
        assertThat(registry.callCountOf(key)).isEqualTo(1);
    }

    /** 极简字段提取（避免给测试引入 JSON 依赖路径分歧）。 */
    static String extract(String json, String field) {
        String token = "\"" + field + "\":\"";
        int i = json.indexOf(token);
        if (i < 0) {
            String b = "\"" + field + "\":";
            int j = json.indexOf(b);
            if (j < 0) {
                return null;
            }
            int from = j + b.length();
            int end = from;
            while (end < json.length() && ",}\" ".indexOf(json.charAt(end)) < 0) {
                end++;
            }
            return json.substring(from, end);
        }
        int from = i + token.length();
        int to = json.indexOf('"', from);
        return json.substring(from, to);
    }
}
