package com.minigenesys.telephony.kafka;

import com.minigenesys.common.dto.RoutingEvent;
import com.minigenesys.telephony.service.TelephonyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoutingEventConsumer {

    private final TelephonyService telephonyService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @KafkaListener(topics = "routing-events", groupId = "telephony-service-group")
    public void consume(String message) {
        log.info("Received routing event message: {}", message);
        try {
            RoutingEvent event = objectMapper.readValue(message, RoutingEvent.class);
            telephonyService.handleAssignment(event);
        } catch (Exception e) {
            log.error("Failed to parse routing event: {}", message, e);
            throw new RuntimeException("Failed to process Kafka message, throwing to trigger DLQ", e);
        }
    }
}
