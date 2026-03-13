package com.paymentplatform.flagsdk.event;

import com.paymentplatform.flagsdk.cache.FlagCacheManager;
import com.paymentplatform.flagsdk.client.FlagServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

public class FlagEventListener {

    private static final Logger log = LoggerFactory.getLogger(FlagEventListener.class);

    private final FlagCacheManager cacheManager;
    private final FlagServerClient serverClient;

    public FlagEventListener(FlagCacheManager cacheManager, FlagServerClient serverClient) {
        this.cacheManager = cacheManager;
        this.serverClient = serverClient;
    }

    @KafkaListener(
            topics = "${feature-flag.kafka.topic:feature-flag-events}",
            groupId = "feature-flag-${feature-flag.instance-id:#{T(java.util.UUID).randomUUID().toString()}}",
            containerFactory = "featureFlagKafkaListenerContainerFactory"
    )
    public void onFlagEvent(FlagEvent event) {
        log.info("Received flag event: type={}, flagKey={}, environment={}, activationId={}",
                event.eventType(), event.flagKey(), event.environment(), event.activationId());

        switch (event.eventType()) {
            case FLAG_PREPARE -> handlePrepare(event);
            case FLAG_COMMIT -> handleCommit(event);
            case FLAG_ROLLBACK -> handleRollback(event);
        }
    }

    private void handlePrepare(FlagEvent event) {
        cacheManager.storePending(
                event.activationId(),
                event.flagKey(),
                event.environment(),
                event.newEnabled()
        );
        serverClient.ack(event.activationId());
        log.info("Processed PREPARE and sent ACK for activation {}", event.activationId());
    }

    private void handleCommit(FlagEvent event) {
        cacheManager.commitPending(
                event.activationId(),
                event.flagKey(),
                event.environment()
        );
        log.info("Committed activation {} for flag {}:{}", event.activationId(), event.flagKey(), event.environment());
    }

    private void handleRollback(FlagEvent event) {
        cacheManager.rollbackPending(
                event.activationId(),
                event.flagKey(),
                event.environment()
        );
        log.info("Rolled back activation {} for flag {}:{}", event.activationId(), event.flagKey(), event.environment());
    }
}
