package com.paymentplatform.flagsdk.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean implementation as the active implementation when the specified feature flag is disabled.
 * Used with the Strategy pattern: the SDK creates a dynamic proxy that routes to this impl when the flag is OFF.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FeatureFlagDisabled {
    String value();
}
