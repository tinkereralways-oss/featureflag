package com.paymentplatform.flagsdk.aop;

import com.paymentplatform.flagsdk.FeatureFlagService;
import com.paymentplatform.flagsdk.annotation.FeatureToggle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeatureToggleAspectTest {

    @Mock
    private FeatureFlagService featureFlagService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    private FeatureToggleAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new FeatureToggleAspect(featureFlagService);
    }

    @Test
    void whenFlagEnabled_proceedsNormally() throws Throwable {
        FeatureToggle toggle = SampleService.class.getMethod("newMethod", String.class)
                .getAnnotation(FeatureToggle.class);

        when(featureFlagService.isEnabled("test-flag")).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("new-result");
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getName()).thenReturn("newMethod");

        Object result = aspect.aroundFeatureToggle(joinPoint, toggle);

        assertEquals("new-result", result);
        verify(joinPoint).proceed();
    }

    @Test
    void whenFlagDisabled_invokesFallback() throws Throwable {
        FeatureToggle toggle = SampleService.class.getMethod("newMethod", String.class)
                .getAnnotation(FeatureToggle.class);

        SampleService target = new SampleService();
        when(featureFlagService.isEnabled("test-flag")).thenReturn(false);
        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"input"});
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getParameterTypes()).thenReturn(new Class<?>[]{String.class});
        when(methodSignature.getName()).thenReturn("newMethod");

        Object result = aspect.aroundFeatureToggle(joinPoint, toggle);

        assertEquals("legacy:input", result);
    }

    // Test helper class
    public static class SampleService {
        @FeatureToggle(flag = "test-flag", fallbackMethod = "legacyMethod")
        public String newMethod(String input) {
            return "new:" + input;
        }

        public String legacyMethod(String input) {
            return "legacy:" + input;
        }
    }
}
