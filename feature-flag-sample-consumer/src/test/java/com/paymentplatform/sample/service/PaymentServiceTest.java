package com.paymentplatform.sample.service;

import com.paymentplatform.flagsdk.FeatureFlagService;
import com.paymentplatform.sample.gateway.PaymentGateway;
import com.paymentplatform.sample.gateway.PaymentResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private FeatureFlagService featureFlagService;

    @Test
    void processPayment_delegatesToGateway() {
        PaymentService service = new PaymentService(paymentGateway, featureFlagService);

        when(paymentGateway.gatewayName()).thenReturn("legacy");
        when(paymentGateway.charge(eq("ORD-1"), anyLong()))
                .thenReturn(new PaymentResult("ORD-1", 10300, "legacy", "TXN-1", true));

        PaymentResult result = service.processPayment("ORD-1", 10000);

        assertNotNull(result);
        assertEquals("ORD-1", result.orderId());
        assertEquals("legacy", result.gateway());
        assertTrue(result.success());

        verify(paymentGateway).charge(eq("ORD-1"), anyLong());
    }

    @Test
    void legacyCalculateFee_threePercent() {
        PaymentService service = new PaymentService(paymentGateway, featureFlagService);

        long fee = service.legacyCalculateFee(10000);
        assertEquals(300, fee);
    }

    @Test
    void legacyCalculateFee_roundsUp() {
        PaymentService service = new PaymentService(paymentGateway, featureFlagService);

        long fee = service.legacyCalculateFee(1);
        assertEquals(1, fee); // 1 * 0.03 = 0.03 → ceil = 1
    }

    @Test
    void getActiveGateway_delegatesToGateway() {
        PaymentService service = new PaymentService(paymentGateway, featureFlagService);

        when(paymentGateway.gatewayName()).thenReturn("stripe");

        assertEquals("stripe", service.getActiveGateway());
    }

    @Test
    void isFlagEnabled_delegatesToService() {
        PaymentService service = new PaymentService(paymentGateway, featureFlagService);

        when(featureFlagService.isEnabled("test-flag")).thenReturn(true);

        assertTrue(service.isFlagEnabled("test-flag"));
        verify(featureFlagService).isEnabled("test-flag");
    }
}
