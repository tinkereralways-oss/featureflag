package com.paymentplatform.flagserver.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaEventPublisherTest {

    @Mock
    private KafkaTemplate<String, FlagEvent> kafkaTemplate;

    @Captor
    private ArgumentCaptor<FlagEvent> eventCaptor;

    private KafkaEventPublisher createPublisher() {
        return new KafkaEventPublisher(kafkaTemplate, "feature-flag-events");
    }

    @Test
    void publishPrepare_sendsCorrectEvent() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        KafkaEventPublisher publisher = createPublisher();
        publisher.publishPrepare("act-1", "my-flag", "flag-id", "prod", true);

        verify(kafkaTemplate).send(eq("feature-flag-events"), eq("my-flag"), eventCaptor.capture());
        FlagEvent event = eventCaptor.getValue();
        assertEquals(FlagEventType.FLAG_PREPARE, event.eventType());
        assertEquals("act-1", event.activationId());
        assertEquals("my-flag", event.flagKey());
        assertEquals("flag-id", event.flagId());
        assertEquals("prod", event.environment());
        assertTrue(event.newEnabled());
        assertNotNull(event.timestamp());
    }

    @Test
    void publishCommit_sendsCorrectEvent() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        KafkaEventPublisher publisher = createPublisher();
        publisher.publishCommit("act-1", "my-flag", "flag-id", "prod", true);

        verify(kafkaTemplate).send(eq("feature-flag-events"), eq("my-flag"), eventCaptor.capture());
        FlagEvent event = eventCaptor.getValue();
        assertEquals(FlagEventType.FLAG_COMMIT, event.eventType());
    }

    @Test
    void publishRollback_sendsCorrectEvent() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        KafkaEventPublisher publisher = createPublisher();
        publisher.publishRollback("act-1", "my-flag", "flag-id", "prod", false);

        verify(kafkaTemplate).send(eq("feature-flag-events"), eq("my-flag"), eventCaptor.capture());
        FlagEvent event = eventCaptor.getValue();
        assertEquals(FlagEventType.FLAG_ROLLBACK, event.eventType());
        assertFalse(event.newEnabled());
    }
}
