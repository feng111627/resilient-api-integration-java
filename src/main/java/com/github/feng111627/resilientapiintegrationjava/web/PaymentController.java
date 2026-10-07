package com.github.feng111627.resilientapiintegrationjava.web;

import com.github.feng111627.resilientapiintegrationjava.model.PaymentRequest;
import com.github.feng111627.resilientapiintegrationjava.model.PaymentResult;
import com.github.feng111627.resilientapiintegrationjava.service.PaymentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下单 REST 入口。
 * 幂等键通过请求头 {@code Idempotency-Key} 传入，必填且不能为空串。
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResult> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 交给 GlobalExceptionHandler 统一成 MISSING_IDEMPOTENCY_KEY
            throw new MissingIdempotencyKeyException();
        }
        PaymentResult result = paymentService.createPayment(idempotencyKey.trim(), request);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        log.info("下单完成 key={} paymentId={} replayed={}",
                idempotencyKey, result.paymentId(), result.replayed());
        return ResponseEntity.status(status).body(result);
    }
}
