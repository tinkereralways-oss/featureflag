package com.paymentplatform.sample.gateway;

public record PaymentResult(
        String orderId,
        long amountCents,
        String gateway,
        String transactionId,
        boolean success
) {
}
