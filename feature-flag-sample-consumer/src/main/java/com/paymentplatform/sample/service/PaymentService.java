package com.paymentplatform.sample.service;

import com.paymentplatform.flagsdk.FeatureFlagService;
import com.paymentplatform.flagsdk.annotation.FeatureToggle;
import com.paymentplatform.sample.gateway.PaymentGateway;
import com.paymentplatform.sample.gateway.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentGateway paymentGateway;
    private final FeatureFlagService featureFlagService;

    public PaymentService(PaymentGateway paymentGateway, FeatureFlagService featureFlagService) {
        this.paymentGateway = paymentGateway;
        this.featureFlagService = featureFlagService;
    }

    public PaymentResult processPayment(String orderId, long amountCents) {
        long fee = calculateFee(amountCents);
        long totalAmount = amountCents + fee;

        log.info("Processing payment: orderId={} amount={} fee={} total={} gateway={}",
                orderId, amountCents, fee, totalAmount, paymentGateway.gatewayName());

        return paymentGateway.charge(orderId, totalAmount);
    }

    @FeatureToggle(flag = "new-fee-calculation", fallbackMethod = "legacyCalculateFee")
    public long calculateFee(long amountCents) {
        // New fee: 2.5% with minimum 50 cents
        long fee = BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(0.025))
                .setScale(0, RoundingMode.CEILING)
                .longValue();
        return Math.max(fee, 50);
    }

    public long legacyCalculateFee(long amountCents) {
        // Legacy fee: flat 3%
        return BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(0.03))
                .setScale(0, RoundingMode.CEILING)
                .longValue();
    }

    public String getActiveGateway() {
        return paymentGateway.gatewayName();
    }

    public boolean isFlagEnabled(String flagKey) {
        return featureFlagService.isEnabled(flagKey);
    }
}
