package com.paymentplatform.flagsdk.condition;

import com.paymentplatform.flagsdk.annotation.FeatureFlagConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * Spring Condition implementation that evaluates feature flag state.
 * Used by @FeatureFlagConfiguration to conditionally activate bean graphs.
 *
 * At application startup, this checks a property-based override:
 * feature-flag.flags.{flagKey}.enabled=true/false
 *
 * This is evaluated at bean definition time (early in the context lifecycle),
 * so it uses properties rather than the runtime flag service.
 */
public class FeatureFlagCondition implements Condition {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagCondition.class);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(
                FeatureFlagConfiguration.class.getName());

        if (attributes == null) {
            return false;
        }

        String flagKey = (String) attributes.get("value");
        if (flagKey == null || flagKey.isEmpty()) {
            return false;
        }

        String propertyKey = "feature-flag.flags." + flagKey + ".enabled";
        String value = context.getEnvironment().getProperty(propertyKey);

        boolean enabled = "true".equalsIgnoreCase(value);
        log.debug("FeatureFlagCondition for flag '{}': property '{}' = '{}', enabled = {}",
                flagKey, propertyKey, value, enabled);

        return enabled;
    }
}
