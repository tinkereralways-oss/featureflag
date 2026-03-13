package com.paymentplatform.flagserver.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, FlagEvent> kafkaTemplate;
    private final String topicName;

    public KafkaEventPublisher(KafkaTemplate<String, FlagEvent> kafkaTemplate,
                               @Value("${feature-flag.kafka.topic:feature-flag-events}") String topicName) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicName;
    }

    public void publishPrepare(String activationId, String flagKey, String flagId,
                               String environment, boolean newEnabled) {
        FlagEvent event = FlagEvent.prepare(activationId, flagKey, flagId, environment, newEnabled);
        publish(flagKey, event);
        log.info("Published FLAG_PREPARE for flag={} env={} activationId={}", flagKey, environment, activationId);
    }

    public void publishCommit(String activationId, String flagKey, String flagId,
                              String environment, boolean newEnabled) {
        FlagEvent event = FlagEvent.commit(activationId, flagKey, flagId, environment, newEnabled);
        publish(flagKey, event);
        log.info("Published FLAG_COMMIT for flag={} env={} activationId={}", flagKey, environment, activationId);
    }

    public void publishRollback(String activationId, String flagKey, String flagId,
                                String environment, boolean newEnabled) {
        FlagEvent event = FlagEvent.rollback(activationId, flagKey, flagId, environment, newEnabled);
        publish(flagKey, event);
        log.info("Published FLAG_ROLLBACK for flag={} env={} activationId={}", flagKey, environment, activationId);
    }

    private void publish(String flagKey, FlagEvent event) {
        kafkaTemplate.send(topicName, flagKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} for flag={}", event.eventType(), flagKey, ex);
                    }
                });
    }
}
