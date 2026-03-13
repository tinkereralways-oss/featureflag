package com.paymentplatform.flagsdk.heartbeat;

import com.paymentplatform.flagsdk.client.FlagServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class HeartbeatManager {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatManager.class);

    private final FlagServerClient serverClient;

    public HeartbeatManager(FlagServerClient serverClient) {
        this.serverClient = serverClient;
    }

    @Scheduled(fixedDelayString = "#{${feature-flag.heartbeat-interval-seconds:15} * 1000}")
    public void sendHeartbeat() {
        log.debug("Sending heartbeat...");
        serverClient.heartbeat();
    }
}
