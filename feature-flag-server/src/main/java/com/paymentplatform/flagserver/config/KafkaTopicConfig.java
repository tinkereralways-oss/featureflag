package com.paymentplatform.flagserver.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${feature-flag.kafka.topic:feature-flag-events}")
    private String topicName;

    @Value("${feature-flag.kafka.partitions:3}")
    private int partitions;

    @Value("${feature-flag.kafka.replication-factor:1}")
    private int replicationFactor;

    @Bean
    public NewTopic featureFlagEventsTopic() {
        return TopicBuilder.name(topicName)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }
}
