package com.minigenesys.audit.config;

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
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
            (record, ex) -> {
                log.error("Retries exhausted for message in topic {}. Sending to DLQ. Error: {}", 
                    record.topic(), ex.getMessage());
                return new org.apache.kafka.common.TopicPartition(record.topic() + ".DLQ", record.partition());
            });

        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(10000L); 
        
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> {
            log.info("Retry attempt {} for message in topic {} due to error: {}", 
                deliveryAttempt, record.topic(), ex.getMessage());
        });

        return errorHandler;
    }
}
