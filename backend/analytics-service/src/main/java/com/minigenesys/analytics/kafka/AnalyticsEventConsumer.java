package com.minigenesys.analytics.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.analytics.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsEventConsumer {

    private final AnalyticsService analyticsService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = {"call-events", "routing-events", "agent-events", "call-lifecycle-events"},
        groupId = "analytics-service-group"
    )
    public void consume(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) throws Exception {
        log.debug("Consumed event from topic {}: {}", topic, message);

        JsonNode node = objectMapper.readTree(message);
        String tenantId = node.has("tenantId") ? node.get("tenantId").asText() : null;

        if (tenantId == null) return;

        switch (topic) {
            case "call-events" -> handleCallEvent(tenantId, node);
            case "routing-events" -> handleRoutingEvent(tenantId, node);
            case "agent-events" -> handleAgentEvent(tenantId, node);
            case "call-lifecycle-events" -> handleLifecycleEvent(tenantId, node);
        }
    }

    private void handleCallEvent(String tenantId, JsonNode node) {
        boolean isNew = !node.has("isNew") || node.get("isNew").asBoolean();
        if (isNew) {
            analyticsService.incrementTotalCalls(tenantId);
        }
        analyticsService.incrementQueuedCalls(tenantId);
    }

    private void handleRoutingEvent(String tenantId, JsonNode node) {
        String status = node.has("status") ? node.get("status").asText() : "";
        if ("ASSIGNED".equals(status)) {
            analyticsService.incrementRoutedCalls(tenantId);
            analyticsService.decrementQueuedCalls(tenantId);
        } else if ("NO_AGENT".equals(status)) {
            analyticsService.incrementNoAgentEvents(tenantId);
        } else if ("ABANDONED".equals(status)) {
            analyticsService.incrementAbandonedCalls(tenantId);
            analyticsService.decrementQueuedCalls(tenantId);
        }
    }

    private void handleAgentEvent(String tenantId, JsonNode node) {
        String oldStatus = node.has("previousStatus") ? node.get("previousStatus").asText() : null;
        String newStatus = node.has("newStatus") ? node.get("newStatus").asText() : null;
        
        analyticsService.updateAgentCounts(tenantId, oldStatus, newStatus);
    }

    private void handleLifecycleEvent(String tenantId, JsonNode node) {
        String eventType = node.has("eventType") ? node.get("eventType").asText() : "";
        if ("CALL_COMPLETED".equals(eventType)) {
            analyticsService.incrementCompletedCalls(tenantId);
        }
    }
}
