package com.minigenesys.routing.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.routing.dto.AssignmentResult;
import com.minigenesys.routing.dto.CallRequest;
import com.minigenesys.routing.service.QueueManager;
import com.minigenesys.routing.service.RoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaMessaging {

    private final RoutingService routingService;
    private final QueueManager queueManager;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String CALL_EVENTS_TOPIC = "call-events";
    private static final String ROUTING_EVENTS_TOPIC = "routing-events";

    @KafkaListener(topics = CALL_EVENTS_TOPIC, groupId = "routing-service-group")
    public void consumeCallEvent(String message) {
        log.info("Consumed call event: {}", message);
        try {
            CallRequest request = objectMapper.readValue(message, CallRequest.class);
            AssignmentResult result = routingService.processRouting(request);
            produceRoutingEvent(result);

            // If no agent available, enqueue the call for retry
            if ("NO_AGENT".equals(result.getStatus())) {
                queueManager.enqueue(request);
                log.info("Call {} enqueued for retry", request.getCallId());
            }
        } catch (Exception e) {
            log.error("Failed to process call event: ", e);
        }
    }

    public void produceRoutingEvent(AssignmentResult result) {
        log.info("Producing routing event for callId: {}, status: {}", result.getCallId(), result.getStatus());
        try {
            String message = objectMapper.writeValueAsString(result);
            // Block to ensure publish success
            kafkaTemplate.send(ROUTING_EVENTS_TOPIC, result.getTenantId(), message).get();
        } catch (Exception e) {
            log.error("Failed to serialize routing result: ", e);
            throw new RuntimeException("Kafka publish failed", e);
        }
    }
}
