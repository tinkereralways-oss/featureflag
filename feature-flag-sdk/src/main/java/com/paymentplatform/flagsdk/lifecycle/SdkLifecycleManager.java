package com.paymentplatform.flagsdk.lifecycle;

import com.paymentplatform.flagsdk.cache.FlagCacheManager;
import com.paymentplatform.flagsdk.client.FlagServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Map;

public class SdkLifecycleManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SdkLifecycleManager.class);

    private final FlagServerClient serverClient;
    private final FlagCacheManager cacheManager;
    private volatile boolean running = false;

    public SdkLifecycleManager(FlagServerClient serverClient, FlagCacheManager cacheManager) {
        this.serverClient = serverClient;
        this.cacheManager = cacheManager;
    }

    @Override
    public void start() {
        log.info("Starting Feature Flag SDK lifecycle...");
        serverClient.register();

        Map<String, Map<String, Boolean>> flags = serverClient.syncFlags();
        cacheManager.loadAll(flags);

        running = true;
        log.info("Feature Flag SDK started successfully");
    }

    @Override
    public void stop() {
        log.info("Stopping Feature Flag SDK...");
        serverClient.deregister();
        running = false;
        log.info("Feature Flag SDK stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
