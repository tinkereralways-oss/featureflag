package com.paymentplatform.flagsdk;

import com.paymentplatform.flagsdk.cache.FlagCacheManager;
import com.paymentplatform.flagsdk.config.FeatureFlagProperties;
import com.paymentplatform.flagsdk.filter.FeatureFlagRequestFilter;

import java.util.Map;

public class FeatureFlagService {

    private final FlagCacheManager cacheManager;
    private final FeatureFlagProperties properties;

    public FeatureFlagService(FlagCacheManager cacheManager, FeatureFlagProperties properties) {
        this.cacheManager = cacheManager;
        this.properties = properties;
    }

    /**
     * Check if a flag is enabled for the configured default environment.
     */
    public boolean isEnabled(String flagKey) {
        return isEnabled(flagKey, properties.getEnvironment());
    }

    /**
     * Check if a flag is enabled for a specific environment.
     * If request-scoped pinning is active, uses the pinned snapshot.
     */
    public boolean isEnabled(String flagKey, String environment) {
        Map<String, Boolean> pinned = FeatureFlagRequestFilter.getPinnedFlags();
        if (pinned != null) {
            String cacheKey = flagKey + ":" + environment;
            Boolean pinnedValue = pinned.get(cacheKey);
            if (pinnedValue != null) {
                return pinnedValue;
            }
        }
        return cacheManager.isEnabled(flagKey, environment);
    }
}
