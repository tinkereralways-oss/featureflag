package com.paymentplatform.sample.gateway;

import com.paymentplatform.flagsdk.annotation.FeatureFlagEnabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@FeatureFlagEnabled("new-payment-gateway")
public class StripePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

    @Override
    public PaymentResult charge(String orderId, long amountCents) {
        log.info("Processing payment via STRIPE gateway: orderId={} amount={}", orderId, amountCents);
        String txnId = "STRIPE-" + UUID.randomUUID().toString().substring(0, 8);
        return new PaymentResult(orderId, amountCents, "stripe", txnId, true);
    }

    @Override
    public String gatewayName() {
        return "stripe";
    }
}
