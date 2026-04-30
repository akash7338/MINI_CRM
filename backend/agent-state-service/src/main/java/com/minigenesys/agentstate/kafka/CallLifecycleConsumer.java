package com.minigenesys.agentstate.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.common.dto.CallLifecycleEvent;
import com.minigenesys.agentstate.service.AgentStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CallLifecycleConsumer {

    private final ObjectMapper objectMapper;
    private final AgentStateService agentStateService;

    private static final String CALL_LIFECYCLE_EVENTS_TOPIC = "call-lifecycle-events";

    @KafkaListener(topics = CALL_LIFECYCLE_EVENTS_TOPIC, groupId = "agent-state-service-group")
    public void consumeLifecycleEvent(String message) {
        log.info("Consumed call lifecycle event: {}", message);
        try {
            CallLifecycleEvent event = objectMapper.readValue(message, CallLifecycleEvent.class);
            if ("CALL_COMPLETED".equals(event.getEventType())) {
                agentStateService.handleCallCompletion(event);
            }
        } catch (Exception e) {
            log.error("Failed to process call lifecycle event: ", e);
            throw new RuntimeException("Failed to process Kafka message, throwing to trigger DLQ", e);
        }
    }
}
