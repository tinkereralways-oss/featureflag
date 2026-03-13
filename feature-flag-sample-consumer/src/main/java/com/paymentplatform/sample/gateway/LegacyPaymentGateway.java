package com.paymentplatform.sample.gateway;

import com.paymentplatform.flagsdk.annotation.FeatureFlagDisabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@FeatureFlagDisabled("new-payment-gateway")
public class LegacyPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(LegacyPaymentGateway.class);

    @Override
    public PaymentResult charge(String orderId, long amountCents) {
        log.info("Processing payment via LEGACY gateway: orderId={} amount={}", orderId, amountCents);
        String txnId = "LEGACY-" + UUID.randomUUID().toString().substring(0, 8);
        return new PaymentResult(orderId, amountCents, "legacy", txnId, true);
    }

    @Override
    public String gatewayName() {
        return "legacy";
    }
}
