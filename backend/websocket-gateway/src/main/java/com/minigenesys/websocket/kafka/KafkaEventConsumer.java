package com.minigenesys.websocket.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.common.dto.RealtimeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.kafka.support.KafkaHeaders;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaEventConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

   @KafkaListener(
    topics = {"call-events", "routing-events", "agent-events", "call-lifecycle-events"},
    groupId = "websocket-gateway-group"
)
public void consume(
        String message,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
) {
    log.debug("Consumed event from topic {}: {}", topic, message);

    try {
        Object payload;
        String tenantId = null;

        try {
            JsonNode node = objectMapper.readTree(message);
            payload = node;

            if (node.has("tenantId")) {
                tenantId = node.get("tenantId").asText();
            }
        } catch (Exception e) {
            payload = message;
        }

        RealtimeEvent event = RealtimeEvent.builder()
                .topic(topic)
                .payload(payload)
                .receivedAt(Instant.now())
                .build();

        if (tenantId != null && !tenantId.isBlank()) {
            messagingTemplate.convertAndSend("/topic/events/" + tenantId, event);
        }

    } catch (Exception e) {
        log.error("Error processing Kafka event from topic {}: {}", topic, e.getMessage(), e);
        throw new RuntimeException("Failed to process Kafka message, throwing to trigger DLQ", e);
    }
}
}