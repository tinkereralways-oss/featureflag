package com.paymentplatform.flagsdk.event;

import com.paymentplatform.flagsdk.cache.FlagCacheManager;
import com.paymentplatform.flagsdk.client.FlagServerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlagEventListenerTest {

    @Mock
    private FlagCacheManager cacheManager;

    @Mock
    private FlagServerClient serverClient;

    private FlagEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new FlagEventListener(cacheManager, serverClient);
    }

    @Test
    void handlePrepare_storesPendingAndAcks() {
        FlagEvent event = new FlagEvent("act-1", FlagEventType.FLAG_PREPARE,
                "my-flag", "flag-id", "prod", true, Instant.now());

        listener.onFlagEvent(event);

        verify(cacheManager).storePending("act-1", "my-flag", "prod", true);
        verify(serverClient).ack("act-1");
    }

    @Test
    void handleCommit_commitsPendingState() {
        FlagEvent event = new FlagEvent("act-1", FlagEventType.FLAG_COMMIT,
                "my-flag", "flag-id", "prod", true, Instant.now());

        listener.onFlagEvent(event);

        verify(cacheManager).commitPending("act-1", "my-flag", "prod");
        verify(serverClient, never()).ack(anyString());
    }

    @Test
    void handleRollback_rollsPendingState() {
        FlagEvent event = new FlagEvent("act-1", FlagEventType.FLAG_ROLLBACK,
                "my-flag", "flag-id", "prod", true, Instant.now());

        listener.onFlagEvent(event);

        verify(cacheManager).rollbackPending("act-1", "my-flag", "prod");
        verify(serverClient, never()).ack(anyString());
    }
}
