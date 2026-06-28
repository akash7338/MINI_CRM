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
        topics = {"call-events", "routing-events", "agent-events", "call-lifecycle-events", "auth-events"},
        groupId = "websocket-gateway-group"
    )
    public void consume(
            String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
    ) {
        log.debug("Consumed event from topic {}: {}", topic, message);

        try {
            JsonNode node = objectMapper.readTree(message);

            // auth-events carry per-user notifications (e.g. LogoutNotification).
            // Route them to the user's personal STOMP topic instead of the shared
            // tenant topic so only the target user's tab receives them.
            if ("auth-events".equals(topic)) {
                handleAuthEvent(node);
                return;
            }

            String tenantId = node.has("tenantId") ? node.get("tenantId").asText() : null;

            RealtimeEvent event = RealtimeEvent.builder()
                    .topic(topic)
                    .payload(node)
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

    /**
     * Handles events on the "auth-events" Kafka topic.
     *
     * LogoutNotification: pushed to /topic/{tenantId}/user/{userId} so only the
     * target user's STOMP subscription receives it. The old tab's Angular handler
     * sees this message, stores a "reason" in localStorage, and shows the login screen.
     */
    private void handleAuthEvent(JsonNode node) {
        String type     = node.path("type").asText();
        String tenantId = node.path("tenantId").asText();
        String userId   = node.path("userId").asText();

        if ("LogoutNotification".equals(type) && !tenantId.isBlank() && !userId.isBlank()) {
            String destination = "/topic/" + tenantId + "/user/" + userId;
            messagingTemplate.convertAndSend(destination, node);
            log.info("LogoutNotification pushed to {}", destination);
        }
    }
}