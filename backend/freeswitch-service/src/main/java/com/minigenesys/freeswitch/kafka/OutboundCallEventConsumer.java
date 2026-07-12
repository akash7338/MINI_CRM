package com.minigenesys.freeswitch.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.common.dto.OutboundCallEvent;
import com.minigenesys.freeswitch.service.FreeswitchCallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboundCallEventConsumer {

    private final FreeswitchCallService freeswitchCallService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "outbound-call-events", groupId = "freeswitch-service-group")
    public void consume(String message) {
        log.info("Received outbound call event message in FreeSWITCH service: {}", message);
        try {
            OutboundCallEvent event = objectMapper.readValue(message, OutboundCallEvent.class);
            
            // Only process if telephonyProvider is FREESWITCH
            if ("FREESWITCH".equals(event.getTelephonyProvider())) {
                freeswitchCallService.handleOutboundCall(event);
            } else {
                log.info("Skipping outbound call event for telephonyProvider: {}", event.getTelephonyProvider());
            }
        } catch (Exception e) {
            log.error("Failed to parse outbound call event: {}", message, e);
            throw new RuntimeException("Failed to process Kafka message, throwing to trigger DLQ", e);
        }
    }
}
