package com.paymentplatform.flagsdk.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level annotation for AOP-based feature toggling.
 * When the flag is enabled, the annotated method executes normally.
 * When the flag is disabled, the fallback method is invoked instead.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FeatureToggle {
    String flag();
    String fallbackMethod();
}
