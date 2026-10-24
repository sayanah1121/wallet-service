package com.sayan.wallet.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaConfig {
    public static final String TRANSFER_TOPIC = "transfer-completed";

    @Bean
    NewTopic transferCompletedTopic() {
        return TopicBuilder.name(TRANSFER_TOPIC).partitions(3).replicas(1).build();
    }

    // The dead-letter topic needs the same partition count as the source topic
    @Bean
    NewTopic transferCompletedDlt() {
        return TopicBuilder.name(TRANSFER_TOPIC + ".DLT").partitions(3).replicas(1).build();
    }

    // Retry 3 times (1s, 2s, 4s), then publish the record to <topic>.DLT.
    // The resolver is explicit because the recoverer's default destination is <topic>-dlt.
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(8000);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        return new DefaultErrorHandler(recoverer, backOff);
    }
}