package com.minigenesys.routing.service;

import com.minigenesys.routing.dto.AssignmentResult;
import com.minigenesys.routing.dto.CallRequest;
import com.minigenesys.routing.engine.RoutingEngine;
import com.minigenesys.routing.kafka.KafkaMessaging;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetryProcessor {

    private final QueueManager queueManager;
    private final RoutingEngine routingEngine;
    private final KafkaMessaging kafkaMessaging;

    @Scheduled(fixedDelayString = "${routing.retry.interval:5000}")
    public void processQueuedCalls() {
        Set<String> tenantQueueKeys = queueManager.getAllTenantsWithQueues();
        if (tenantQueueKeys == null || tenantQueueKeys.isEmpty()) {
            return;
        }

        for (String key : tenantQueueKeys) {
            // key format: tenant:{tenantId}:queue
            String tenantId = key.split(":")[1];
            processTenantQueue(tenantId);
        }
    }

    private void processTenantQueue(String tenantId) {
        Set<String> callIds = queueManager.getQueuedCallIds(tenantId);
        if (callIds == null || callIds.isEmpty()) {
            return;
        }

        log.debug("Processing {} queued calls for tenant {}", callIds.size(), tenantId);

        for (String callId : callIds) {
            CallRequest call = queueManager.getCallRequest(tenantId, callId);
            if (call == null) {
                queueManager.dequeue(tenantId, callId);
                continue;
            }

            AssignmentResult result = routingEngine.assignAgent(call);
            if (result.isSuccess()) {
                log.info("Queued call {} assigned to agent {}", callId, result.getAgentId());
                queueManager.dequeue(tenantId, callId);
                kafkaMessaging.produceRoutingEvent(result);
            } else {
                // Still no agent or error, keep in queue
                // The assignAgent method would have re-enqueued it if it was a new request, 
                // but since it's already in ZSET, it stays there with original score.
                log.debug("Queued call {} still not assigned: {}", callId, result.getMessage());
                
                // Stop processing this tenant's queue if no agent available 
                // (assuming if one call fails, others behind it with same/less priority will also likely fail)
                if ("NO_AGENT".equals(result.getStatus())) {
                    break; 
                }
            }
        }
    }
}
