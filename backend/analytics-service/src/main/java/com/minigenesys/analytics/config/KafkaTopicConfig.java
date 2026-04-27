package com.minigenesys.analytics.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic callEventsDlq() {
        return TopicBuilder.name("call-events.DLQ").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic routingEventsDlq() {
        return TopicBuilder.name("routing-events.DLQ").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic agentEventsDlq() {
        return TopicBuilder.name("agent-events.DLQ").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic callLifecycleEventsDlq() {
        return TopicBuilder.name("call-lifecycle-events.DLQ").partitions(1).replicas(1).build();
    }
}
