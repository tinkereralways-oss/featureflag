package com.paymentplatform.sample.config;

import com.paymentplatform.flagsdk.annotation.FeatureFlagConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration pattern: this entire configuration class is only activated
 * when the "beta-logging" feature flag is enabled (evaluated at bean
 * definition time via Spring's @Conditional mechanism).
 */
@Configuration
@FeatureFlagConfiguration("beta-logging")
public class BetaFeatureConfig {

    @Bean
    public BetaLoggingEnhancer betaLoggingEnhancer() {
        return new BetaLoggingEnhancer();
    }

    /**
     * A simple logging-enhancement bean that is only active when the
     * "beta-logging" flag is enabled. Logs a startup message and provides
     * an enhanced logging helper method.
     */
    public static class BetaLoggingEnhancer {

        private static final Logger log = LoggerFactory.getLogger(BetaLoggingEnhancer.class);

        public BetaLoggingEnhancer() {
            log.info("Beta logging enhancer activated — enhanced payment logging is enabled");
        }

        public void logPaymentEvent(String orderId, long amountCents, String event) {
            log.info("[BETA-LOG] orderId={} amount={} event={}", orderId, amountCents, event);
        }
    }
}
