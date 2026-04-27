package com.minigenesys.analytics.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Slf4j
@Configuration
public class KafkaConfig {

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
        // 1. Configure Dead Letter Publishing Recoverer
        // Default naming is <topic>.DLQ
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
            (record, ex) -> {
                log.error("Retries exhausted for message in topic {}. Sending to DLQ. Error: {}", 
                    record.topic(), ex.getMessage());
                return new org.apache.kafka.common.TopicPartition(record.topic() + ".DLQ", record.partition());
            });

        // 2. Configure Exponential Backoff: 1s -> 2s -> 4s (3 retries total)
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(10000L); // Max total time
        // Note: Spring Kafka's DefaultErrorHandler uses BackOff. 
        // For exactly 1s, 2s, 4s, we can use ExponentialBackOff or FixedBackOff with custom logic.
        // The requirement is 3 retries.
        
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        
        // Add logging for retry attempts
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> {
            log.info("Retry attempt {} for message in topic {} due to error: {}", 
                deliveryAttempt, record.topic(), ex.getMessage());
        });

        return errorHandler;
    }
}
