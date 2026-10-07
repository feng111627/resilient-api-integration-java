package com.github.feng111627.resilientapiintegrationjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static com.github.feng111627.resilientapiintegrationjava.tests.IdempotencyConcurrencyTest.extract;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务：支付下单错误分类。
 * 覆盖：幂等键内容冲突与普通参数错误必须可区分；缺幂等键；参数不合法。
 */
@DisplayName("业务-支付下单：幂等冲突与参数校验错误分类")
class IdempotencyConflictAndValidationTest extends AbstractIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(IdempotencyConflictAndValidationTest.class);

    private final ApiClient client = new ApiClient();

    @Test
    @DisplayName("同键不同内容：返回 409 IDEMPOTENCY_CONFLICT，且不调用上游第二次")
    void sameKeyDifferentBody_conflict() {
        String key = "idem-conflict-" + UUID.randomUUID();
        String body1 = "{\"amount\":10.00,\"payerAccount\":\"A\",\"payeeAccount\":\"B\"}";
        String body2 = "{\"amount\":99.00,\"payerAccount\":\"A\",\"payeeAccount\":\"B\"}";

        ApiClient.Response r1 = client.postPayment(baseUrl(), key, body1);
        ApiClient.Response r2 = client.postPayment(baseUrl(), key, body2);

        log.info("同键不同内容：r1={} r2.status={} r2.body={}", r1.status(), r2.status(), r2.body());

        assertThat(r1.status()).isEqualTo(201);
        assertThat(r2.status()).isEqualTo(409);
        assertThat(extract(r2.body(), "code")).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(r2.body()).contains("existingRequestHash").contains("incomingRequestHash");
        // 第二次因冲突被拒绝，上游只处理第一次
        assertThat(registry.callCountOf(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("缺少幂等键头：返回 400 MISSING_IDEMPOTENCY_KEY，区别于参数校验错误")
    void missingKey() {
        ApiClient.Response r = client.postPayment(baseUrl(), null,
                "{\"amount\":1,\"payerAccount\":\"A\",\"payeeAccount\":\"B\"}");
        log.info("缺幂等键：status={} body={}", r.status(), r.body());
        assertThat(r.status()).isEqualTo(400);
        assertThat(extract(r.body(), "code")).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    @DisplayName("金额为负：返回 400 VALIDATION_ERROR，与幂等冲突码不同")
    void invalidAmount() {
        ApiClient.Response r = client.postPayment(baseUrl(), "k-" + UUID.randomUUID(),
                "{\"amount\":-5,\"payerAccount\":\"A\",\"payeeAccount\":\"B\"}");
        log.info("非法金额：status={} body={}", r.status(), r.body());
        assertThat(r.status()).isEqualTo(400);
        assertThat(extract(r.body(), "code")).isEqualTo("VALIDATION_ERROR");
        assertThat(r.body()).contains("fields").contains("amount");
    }

    @Test
    @DisplayName("空请求体 / 坏 JSON：返回 400 VALIDATION_ERROR")
    void malformedJson() {
        ApiClient.Response r = client.postPayment(baseUrl(), "k-" + UUID.randomUUID(), "{bad");
        log.info("坏 JSON：status={} body={}", r.status(), r.body());
        assertThat(r.status()).isEqualTo(400);
        assertThat(extract(r.body(), "code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("内容相同但字段顺序不同：视为同一请求（规范化指纹），允许重放")
    void reorderedFields_sameFingerprint() {
        String key = "idem-reorder-" + UUID.randomUUID();
        String body1 = "{\"amount\":7.5,\"payerAccount\":\"A\",\"payeeAccount\":\"B\"}";
        String body2 = "{\"payeeAccount\":\"B\",\"amount\":7.5,\"payerAccount\":\"A\"}";

        ApiClient.Response r1 = client.postPayment(baseUrl(), key, body1);
        ApiClient.Response r2 = client.postPayment(baseUrl(), key, body2);

        log.info("字段顺序不同：r1={} r2={} replayed={}", r1.status(), r2.status(),
                extract(r2.body(), "replayed"));
        assertThat(r1.status()).isEqualTo(201);
        assertThat(r2.status()).isEqualTo(200);
        assertThat(extract(r2.body(), "replayed")).isEqualTo("true");
        assertThat(registry.callCountOf(key)).isEqualTo(1);
    }
}
