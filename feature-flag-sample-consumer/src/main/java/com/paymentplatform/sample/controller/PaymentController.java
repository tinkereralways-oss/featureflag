package com.paymentplatform.sample.controller;

import com.paymentplatform.flagsdk.annotation.FeatureToggle;
import com.paymentplatform.sample.gateway.PaymentResult;
import com.paymentplatform.sample.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/charge")
    public PaymentResult charge(@RequestParam String orderId,
                                @RequestParam long amountCents) {
        return paymentService.processPayment(orderId, amountCents);
    }

    @GetMapping("/fee")
    public Map<String, Object> calculateFee(@RequestParam long amountCents) {
        long fee = paymentService.calculateFee(amountCents);
        return Map.of(
                "amountCents", amountCents,
                "feeCents", fee,
                "totalCents", amountCents + fee
        );
    }

    /**
     * Route Gating pattern: this endpoint is only accessible when the
     * "beta-payments-api" flag is enabled. When the flag is off, the
     * fallback method throws a 404.
     */
    @GetMapping("/beta/status")
    @FeatureToggle(flag = "beta-payments-api", fallbackMethod = "betaStatusUnavailable")
    public Map<String, Object> betaStatus() {
        return Map.of(
                "beta", true,
                "activeGateway", paymentService.getActiveGateway(),
                "message", "Beta payments API is active"
        );
    }

    private Map<String, Object> betaStatusUnavailable() {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Beta payments API is not available");
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "activeGateway", paymentService.getActiveGateway(),
                "newGatewayEnabled", paymentService.isFlagEnabled("new-payment-gateway"),
                "newFeeEnabled", paymentService.isFlagEnabled("new-fee-calculation")
        );
    }
}
