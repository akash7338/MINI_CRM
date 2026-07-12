package com.minigenesys.callservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.common.dto.CallEvent;
import com.minigenesys.common.dto.CallLifecycleEvent;
import com.minigenesys.common.dto.OutboundCallEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CallEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String CALL_EVENTS_TOPIC = "call-events";
    private static final String CALL_LIFECYCLE_EVENTS_TOPIC = "call-lifecycle-events";
    private static final String OUTBOUND_CALL_EVENTS_TOPIC = "outbound-call-events";

    public void publishCallEvent(CallEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            log.info("Publishing call event: {}", message);
            // Block to ensure publish success so transaction can rollback if it fails
            kafkaTemplate.send(CALL_EVENTS_TOPIC, event.getTenantId(), message).get();
        } catch (Exception e) {
            log.error("Failed to serialize and publish call event", e);
            throw new RuntimeException("Kafka publish failed", e);
        }
    }

    public void publishLifecycleEvent(CallLifecycleEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            log.info("Publishing call lifecycle event: {}", message);
            // Block to ensure publish success
            kafkaTemplate.send(CALL_LIFECYCLE_EVENTS_TOPIC, event.getTenantId(), message).get();
        } catch (Exception e) {
            log.error("Failed to serialize and publish call lifecycle event", e);
            throw new RuntimeException("Kafka publish failed", e);
        }
    }

    public void publishOutboundCallEvent(OutboundCallEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            log.info("Publishing outbound call event: {}", message);
            // Block to ensure publish success so transaction can rollback if it fails
            kafkaTemplate.send(OUTBOUND_CALL_EVENTS_TOPIC, event.getTenantId(), message).get();
        } catch (Exception e) {
            log.error("Failed to serialize and publish outbound call event", e);
            throw new RuntimeException("Kafka publish failed", e);
        }
    }
}
