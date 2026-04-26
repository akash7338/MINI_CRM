package com.minigenesys.agentstate.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.agentstate.dto.AgentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String AGENT_EVENTS_TOPIC = "agent-events";

    public void publishAgentEvent(AgentEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            log.info("Publishing agent event: {}", message);
            kafkaTemplate.send(AGENT_EVENTS_TOPIC, event.getTenantId(), message);
        } catch (Exception e) {
            log.error("Failed to serialize and publish agent event", e);
        }
    }
}
