package com.github.feng111627.resilientapiintegrationjava.web;

import com.github.feng111627.resilientapiintegrationjava.payment.PaymentCommand;
import com.github.feng111627.resilientapiintegrationjava.payment.PaymentResult;
import com.github.feng111627.resilientapiintegrationjava.payment.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 支付业务入口：POST /api/payments，必须携带 Idempotency-Key 头。 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResult> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PaymentCommand command) {
        PaymentResult result = paymentService.createPayment(idempotencyKey, command);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
