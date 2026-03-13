package com.paymentplatform.flagsdk.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FlagCacheManager {

    private static final Logger log = LoggerFactory.getLogger(FlagCacheManager.class);

    private final Cache<String, Boolean> activeCache;
    private final Map<String, Boolean> pendingStates = new ConcurrentHashMap<>();

    public FlagCacheManager() {
        this.activeCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .build();
    }

    public boolean isEnabled(String flagKey, String environment) {
        String cacheKey = buildKey(flagKey, environment);
        Boolean value = activeCache.getIfPresent(cacheKey);
        return value != null && value;
    }

    public void updateFlag(String flagKey, String environment, boolean enabled) {
        String cacheKey = buildKey(flagKey, environment);
        activeCache.put(cacheKey, enabled);
        log.debug("Cache updated: {} = {}", cacheKey, enabled);
    }

    public void loadAll(Map<String, Map<String, Boolean>> flagStates) {
        flagStates.forEach((flagKey, envMap) ->
                envMap.forEach((env, enabled) ->
                        activeCache.put(buildKey(flagKey, env), enabled)));
        log.info("Loaded {} flag keys into cache", flagStates.size());
    }

    public void storePending(String activationId, String flagKey, String environment, boolean newEnabled) {
        String pendingKey = buildPendingKey(activationId, flagKey, environment);
        pendingStates.put(pendingKey, newEnabled);
        log.debug("Stored pending state: {} = {}", pendingKey, newEnabled);
    }

    public void commitPending(String activationId, String flagKey, String environment) {
        String pendingKey = buildPendingKey(activationId, flagKey, environment);
        Boolean newEnabled = pendingStates.remove(pendingKey);
        if (newEnabled != null) {
            activeCache.put(buildKey(flagKey, environment), newEnabled);
            log.info("Committed activation {}: {}:{} = {}", activationId, flagKey, environment, newEnabled);
        } else {
            log.warn("No pending state found for activation {}", activationId);
        }
    }

    public void rollbackPending(String activationId, String flagKey, String environment) {
        String pendingKey = buildPendingKey(activationId, flagKey, environment);
        pendingStates.remove(pendingKey);
        log.info("Rolled back activation {}: {}:{}", activationId, flagKey, environment);
    }

    public Map<String, Boolean> getAllFlags() {
        return Map.copyOf(activeCache.asMap());
    }

    private String buildKey(String flagKey, String environment) {
        return flagKey + ":" + environment;
    }

    private String buildPendingKey(String activationId, String flagKey, String environment) {
        return activationId + ":" + flagKey + ":" + environment;
    }
}
