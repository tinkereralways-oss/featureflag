package com.paymentplatform.flagsdk.annotation;

import com.paymentplatform.flagsdk.condition.FeatureFlagCondition;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Conditional configuration annotation that activates an entire bean graph
 * only when the specified feature flag is enabled.
 * Uses Spring's @Conditional mechanism with FeatureFlagCondition.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(FeatureFlagCondition.class)
public @interface FeatureFlagConfiguration {
    String value();
}
