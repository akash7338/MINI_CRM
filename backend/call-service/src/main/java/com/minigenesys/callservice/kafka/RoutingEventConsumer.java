package com.minigenesys.callservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.callservice.dto.RoutingEvent;
import com.minigenesys.callservice.service.CallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoutingEventConsumer {

    private final ObjectMapper objectMapper;
    private final CallService callService;

    private static final String ROUTING_EVENTS_TOPIC = "routing-events";

    @KafkaListener(topics = ROUTING_EVENTS_TOPIC, groupId = "call-service-group")
    public void consumeRoutingEvent(String message) {
        log.info("Consumed routing event: {}", message);
        try {
            RoutingEvent event = objectMapper.readValue(message, RoutingEvent.class);
            callService.handleRoutingEvent(event);
        } catch (Exception e) {
            log.error("Failed to process routing event: ", e);
        }
    }
}
