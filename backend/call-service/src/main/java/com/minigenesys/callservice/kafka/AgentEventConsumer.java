package com.minigenesys.callservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.common.dto.AgentEvent;
import com.minigenesys.callservice.service.CallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentEventConsumer {

    private final ObjectMapper objectMapper;
    private final CallService callService;

    private static final String AGENT_EVENTS_TOPIC = "agent-events";

    @KafkaListener(topics = AGENT_EVENTS_TOPIC, groupId = "call-service-agent-recovery-group")
    public void consumeAgentEvent(String message) {
        log.info("Consumed agent event for recovery: {}", message);
        try {
            AgentEvent event = objectMapper.readValue(message, AgentEvent.class);
            if ("AGENT_DISCONNECTED".equals(event.getEventType())) {
                callService.handleAgentDisconnect(event);
            }
        } catch (Exception e) {
            log.error("Failed to process agent event for recovery: ", e);
            throw new RuntimeException("Failed to process Kafka message, throwing to trigger DLQ", e);
        }
    }
}
