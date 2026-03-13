package com.paymentplatform.flagsdk.proxy;

import com.paymentplatform.flagsdk.FeatureFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureFlagProxyFactoryTest {

    @Mock
    private FeatureFlagService featureFlagService;

    private FeatureFlagProxyFactory proxyFactory;

    @BeforeEach
    void setUp() {
        proxyFactory = new FeatureFlagProxyFactory(featureFlagService);
    }

    @Test
    void proxy_routesToEnabledImpl_whenFlagOn() {
        when(featureFlagService.isEnabled("payment-gateway")).thenReturn(true);

        PaymentGateway proxy = proxyFactory.createProxy(
                PaymentGateway.class,
                new StripeGateway(),
                new LegacyGateway(),
                "payment-gateway"
        );

        assertEquals("stripe:100", proxy.charge(100));
    }

    @Test
    void proxy_routesToDisabledImpl_whenFlagOff() {
        when(featureFlagService.isEnabled("payment-gateway")).thenReturn(false);

        PaymentGateway proxy = proxyFactory.createProxy(
                PaymentGateway.class,
                new StripeGateway(),
                new LegacyGateway(),
                "payment-gateway"
        );

        assertEquals("legacy:100", proxy.charge(100));
    }

    @Test
    void proxy_switchesAtRuntime() {
        when(featureFlagService.isEnabled("payment-gateway")).thenReturn(false, true);

        PaymentGateway proxy = proxyFactory.createProxy(
                PaymentGateway.class,
                new StripeGateway(),
                new LegacyGateway(),
                "payment-gateway"
        );

        assertEquals("legacy:100", proxy.charge(100));
        assertEquals("stripe:100", proxy.charge(100));
    }

    // Test interfaces and implementations
    interface PaymentGateway {
        String charge(int amount);
    }

    static class StripeGateway implements PaymentGateway {
        @Override
        public String charge(int amount) {
            return "stripe:" + amount;
        }
    }

    static class LegacyGateway implements PaymentGateway {
        @Override
        public String charge(int amount) {
            return "legacy:" + amount;
        }
    }
}
