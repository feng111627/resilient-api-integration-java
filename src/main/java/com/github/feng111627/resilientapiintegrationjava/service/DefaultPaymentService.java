package com.github.feng111627.resilientapiintegrationjava.service;

import com.github.feng111627.resilientapiintegrationjava.client.UpstreamPaymentClient;
import com.github.feng111627.resilientapiintegrationjava.client.json.JsonCodec;
import com.github.feng111627.resilientapiintegrationjava.exception.IntegrationException;
import com.github.feng111627.resilientapiintegrationjava.idempotency.IdempotencyRecord;
import com.github.feng111627.resilientapiintegrationjava.idempotency.IdempotencyState;
import com.github.feng111627.resilientapiintegrationjava.idempotency.IdempotencyStore;
import com.github.feng111627.resilientapiintegrationjava.model.ErrorCode;
import com.github.feng111627.resilientapiintegrationjava.model.PaymentRequest;
import com.github.feng111627.resilientapiintegrationjava.model.PaymentResult;
import com.github.feng111627.resilientapiintegrationjava.resilience.CircuitBreaker;
import com.github.feng111627.resilientapiintegrationjava.resilience.RetryExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 默认下单编排：
 * <ol>
 *   <li>计算请求内容指纹并做幂等抢占（并发去重 / 冲突检测 / 结果重放）。</li>
 *   <li>首个请求：先查熔断器（OPEN 直接快速失败并撤销占位），再交给重试器；
 *       每次真实调用的成败都即时记录熔断，因此连续失败达阈值后后续请求快速失败。</li>
 *   <li>成功结果写入幂等存储；失败写入失败终态，避免等待者/重复提交触发第二次副作用。</li>
 * </ol>
 */
@Service
public class DefaultPaymentService implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPaymentService.class);

    private final IdempotencyStore store;
    private final CircuitBreaker circuitBreaker;
    private final RetryExecutor retryExecutor;
    private final UpstreamPaymentClient client;
    private final JsonCodec jsonCodec;

    public DefaultPaymentService(IdempotencyStore store, CircuitBreaker circuitBreaker,
                                 RetryExecutor retryExecutor, UpstreamPaymentClient client,
                                 JsonCodec jsonCodec) {
        this.store = store;
        this.circuitBreaker = circuitBreaker;
        this.retryExecutor = retryExecutor;
        this.client = client;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public PaymentResult createPayment(String idempotencyKey, PaymentRequest request) {
        String requestHash = fingerprint(request);
        IdempotencyStore.AcquireResult acquired = store.acquire(idempotencyKey, requestHash);

        if (acquired instanceof IdempotencyStore.AcquireResult.Completed c) {
            return replayed(c.result());
        }
        if (acquired instanceof IdempotencyStore.AcquireResult.Failed f) {
            throw previousFailed(idempotencyKey, f.errorCode());
        }
        if (acquired instanceof IdempotencyStore.AcquireResult.Conflict conflict) {
            throw conflict(idempotencyKey, conflict);
        }
        if (acquired instanceof IdempotencyStore.AcquireResult.InFlight) {
            return awaitPeerOutcome(idempotencyKey, request);
        }

        // First：本线程是首个持有者
        return executeAsLeader(idempotencyKey, request);
    }

    private PaymentResult executeAsLeader(String idempotencyKey, PaymentRequest request) {
        if (!circuitBreaker.allowRequest()) {
            // 尚未发起任何上游调用：撤销占位，使冷却后同键请求可以重新执行
            store.abort(idempotencyKey);
            throw circuitOpen(idempotencyKey);
        }
        try {
            PaymentResult result = retryExecutor.executeWithRetry(
                    () -> invokeOnceAndFeedCircuitBreaker(idempotencyKey, request));
            store.complete(idempotencyKey, result);
            return result;
        } catch (IntegrationException e) {
            // 已真正发起过上游调用：无论上游是否实际生效都记录失败终态，
            // 让重复提交重放失败而不是再次触达上游，避免重复副作用。
            // （理论上这里不会是 CIRCUIT_OPEN——该情形已在入口处 abort；保留分支仅作防御。）
            if (e.getErrorCode() == ErrorCode.CIRCUIT_OPEN) {
                store.abort(idempotencyKey);
            } else {
                store.fail(idempotencyKey, e.getErrorCode().name());
            }
            throw e;
        }
    }

    /**
     * 单次真实调用并把成败反馈给熔断器。
     *
     * <p>是否放行只由 {@code executeAsLeader} 入口处的 {@code allowRequest()} 唯一把关
     * （HALF_OPEN 仅放一个试探请求）；重试动作内部不再重复询问，否则会因唯一名额被
     * 外层占用而把合法的试探误判为拒绝。</p>
     */
    private PaymentResult invokeOnceAndFeedCircuitBreaker(String idempotencyKey,
                                                          PaymentRequest request) {
        try {
            PaymentResult result = client.createPayment(idempotencyKey, request);
            circuitBreaker.recordSuccess();
            return result;
        } catch (IntegrationException e) {
            circuitBreaker.recordFailure();
            throw e;
        }
    }

    private PaymentResult awaitPeerOutcome(String idempotencyKey, PaymentRequest request) {
        Optional<IdempotencyRecord> recordOpt;
        try {
            recordOpt = store.waitForCompletion(idempotencyKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegrationException(ErrorCode.INTERNAL_ERROR,
                    "等待同键请求结果时被中断", e,
                    Map.of("idempotencyKey", idempotencyKey), false);
        }
        if (recordOpt.isEmpty()) {
            throw new IntegrationException(ErrorCode.IDEMPOTENCY_IN_FLIGHT,
                    "同一幂等键的请求仍在处理中，请稍后使用相同键查询结果，不要更换内容重试",
                    null, Map.of("idempotencyKey", idempotencyKey), false);
        }
        IdempotencyRecord record = recordOpt.get();
        if (record.state() == IdempotencyState.SUCCEEDED) {
            return replayed(record.result());
        }
        if (record.state() == IdempotencyState.FAILED) {
            throw previousFailed(idempotencyKey, record.errorCode());
        }
        // ABORTED：前一个占位在调用上游前被熔断撤销，等待者用自己的原始请求重新抢占
        return createPayment(idempotencyKey, request);
    }

    private PaymentResult replayed(PaymentResult original) {
        return new PaymentResult(original.paymentId(), original.status(), original.amount(),
                original.idempotencyKey(), true, original.completedAt());
    }

    private IntegrationException conflict(String key,
                                          IdempotencyStore.AcquireResult.Conflict conflict) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("idempotencyKey", key);
        details.put("reason", "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY");
        details.put("existingRequestHash", conflict.existingHash());
        details.put("incomingRequestHash", conflict.incomingHash());
        return new IntegrationException(ErrorCode.IDEMPOTENCY_CONFLICT,
                "幂等键已被用于不同内容的请求，拒绝执行；请为新请求使用新的幂等键",
                null, details, false);
    }

    private IntegrationException previousFailed(String key, String errorCode) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("idempotencyKey", key);
        details.put("originalErrorCode", errorCode);
        details.put("reason", "PREVIOUS_ATTEMPT_FAILED");
        return new IntegrationException(ErrorCode.IDEMPOTENCY_PREVIOUS_FAILED,
                "该幂等键的上一次请求已失败，为避免重复副作用不会自动重试；"
                        + "请确认业务状态后使用新的幂等键",
                null, details, false);
    }

    private IntegrationException circuitOpen(String key) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("idempotencyKey", key);
        details.put("circuitState", circuitBreaker.currentState().name());
        details.put("remainingOpenMillis", circuitBreaker.remainingOpenMillis());
        return new IntegrationException(ErrorCode.CIRCUIT_OPEN,
                "熔断器处于打开状态，暂时快速失败；冷却后将自动试探恢复",
                null, details, false);
    }

    private String fingerprint(PaymentRequest request) {
        String canonical = jsonCodec.stableJson(request);
        return sha256(canonical);
    }

    private static String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
