package com.minigenesys.audit.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.audit.model.AuditEvent;
import com.minigenesys.audit.repository.AuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaAuditConsumer {

    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {"call-events", "routing-events", "agent-events", "call-lifecycle-events", "user-events"},
            groupId = "audit-service-group"
    )
    @Transactional
    public void consume(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.debug("Consuming event from topic {}: {}", topic, message);

        try {
            JsonNode node = objectMapper.readTree(message);

            String tenantId = node.has("tenantId") ? node.get("tenantId").asText() : null;
            String eventType = node.has("eventType") ? node.get("eventType").asText() : topic;
            
            // Extract some common entity IDs if present
            String entityId = null;
            String entityType = null;
            if (node.has("callId")) {
                entityId = node.get("callId").asText();
                entityType = "CALL";
            } else if (node.has("agentId")) {
                entityId = node.get("agentId").asText();
                entityType = "AGENT";
            } else if (node.has("userId")) {
                entityId = node.get("userId").asText();
                entityType = "USER";
            }

            AuditEvent event = AuditEvent.builder()
                    .tenantId(tenantId)
                    .eventType(eventType)
                    .sourceService(getSourceService(topic))
                    .entityType(entityType)
                    .entityId(entityId)
                    .payloadJson(message)
                    .build();

            auditRepository.save(event);

        } catch (Exception e) {
            log.error("Error processing audit event from topic {}: {}", topic, e.getMessage(), e);
        }
    }

    private String getSourceService(String topic) {
        switch (topic) {
            case "call-events":
            case "call-lifecycle-events":
                return "call-service";
            case "routing-events":
                return "routing-service";
            case "agent-events":
                return "agent-state-service";
            case "user-events":
                return "user-service";
            default:
                return "unknown";
        }
    }
}
