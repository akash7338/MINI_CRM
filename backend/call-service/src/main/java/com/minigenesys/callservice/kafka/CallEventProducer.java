package com.minigenesys.callservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.callservice.dto.CallEvent;
import com.minigenesys.callservice.dto.CallLifecycleEvent;
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

    public void publishCallEvent(CallEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            log.info("Publishing call event: {}", message);
            kafkaTemplate.send(CALL_EVENTS_TOPIC, event.getTenantId(), message);
        } catch (Exception e) {
            log.error("Failed to serialize and publish call event", e);
        }
    }

    public void publishLifecycleEvent(CallLifecycleEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            log.info("Publishing call lifecycle event: {}", message);
            kafkaTemplate.send(CALL_LIFECYCLE_EVENTS_TOPIC, event.getTenantId(), message);
        } catch (Exception e) {
            log.error("Failed to serialize and publish call lifecycle event", e);
        }
    }
}
