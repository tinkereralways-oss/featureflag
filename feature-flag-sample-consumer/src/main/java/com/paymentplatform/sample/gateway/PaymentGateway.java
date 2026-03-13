package com.paymentplatform.sample.gateway;

public interface PaymentGateway {

    PaymentResult charge(String orderId, long amountCents);

    String gatewayName();
}
