package com.minigenesys.freeswitch.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.common.dto.RoutingEvent;
import com.minigenesys.freeswitch.service.FreeswitchCallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoutingEventConsumer {

    private final FreeswitchCallService freeswitchCallService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "routing-events", groupId = "freeswitch-service-group")
    public void consume(String message) {
        log.info("Received routing event message in FreeSWITCH service: {}", message);
        try {
            RoutingEvent event = objectMapper.readValue(message, RoutingEvent.class);
            freeswitchCallService.handleAssignment(event);
        } catch (Exception e) {
            log.error("Failed to parse routing event: {}", message, e);
            throw new RuntimeException("Failed to process Kafka message, throwing to trigger DLQ", e);
        }
    }
}
