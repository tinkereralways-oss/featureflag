package com.paymentplatform.flagsdk.aop;

import com.paymentplatform.flagsdk.FeatureFlagService;
import com.paymentplatform.flagsdk.annotation.FeatureToggle;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * AOP aspect that intercepts methods annotated with @FeatureToggle.
 * When the flag is enabled, the annotated method proceeds normally.
 * When the flag is disabled, the specified fallback method is invoked instead.
 */
@Aspect
public class FeatureToggleAspect {

    private static final Logger log = LoggerFactory.getLogger(FeatureToggleAspect.class);

    private final FeatureFlagService featureFlagService;

    public FeatureToggleAspect(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @Around("@annotation(featureToggle)")
    public Object aroundFeatureToggle(ProceedingJoinPoint joinPoint, FeatureToggle featureToggle) throws Throwable {
        String flagKey = featureToggle.flag();
        String fallbackMethodName = featureToggle.fallbackMethod();

        boolean enabled = featureFlagService.isEnabled(flagKey);
        log.debug("Feature flag '{}' is {}, method: {}", flagKey, enabled ? "enabled" : "disabled",
                joinPoint.getSignature().getName());

        if (enabled) {
            return joinPoint.proceed();
        }

        return invokeFallback(joinPoint, fallbackMethodName);
    }

    private Object invokeFallback(ProceedingJoinPoint joinPoint, String fallbackMethodName) throws Throwable {
        Object target = joinPoint.getTarget();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?>[] parameterTypes = signature.getParameterTypes();

        try {
            Method fallbackMethod = target.getClass().getDeclaredMethod(fallbackMethodName, parameterTypes);
            fallbackMethod.setAccessible(true);
            log.debug("Invoking fallback method: {}", fallbackMethodName);
            return fallbackMethod.invoke(target, joinPoint.getArgs());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Fallback method '" + fallbackMethodName + "' not found on " +
                            target.getClass().getSimpleName() + " with parameter types matching " +
                            signature.getMethod().getName(), e);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
