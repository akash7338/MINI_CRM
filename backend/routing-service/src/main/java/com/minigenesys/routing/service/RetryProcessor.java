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

    private static final int MAX_RETRIES = 10;

    // Fibonacci backoff delays in ms, indexed by retryCount (0-based)
    private static final long[] FIBONACCI_BACKOFF_MS = {
        1000, 1000, 2000, 3000, 5000, 8000, 13000, 21000, 30000, 30000
    };

    @Scheduled(fixedDelayString = "${routing.retry.interval:5000}")
    public void processQueuedCalls() {
        Set<String> tenantIds = queueManager.getAllTenantsWithQueues();
        if (tenantIds == null || tenantIds.isEmpty()) {
            return;
        }

        for (String tenantId : tenantIds) {
            processTenantQueue(tenantId);
        }
    }

    private void processTenantQueue(String tenantId) {
        Set<String> callIds = queueManager.getQueuedCallIds(tenantId);
        if (callIds == null || callIds.isEmpty()) {
            return;
        }

        log.info("Processing {} queued calls for tenant {}", callIds.size(), tenantId);

        for (String callId : callIds) {
            long retryCount = queueManager.getRetryCount(tenantId, callId);

            // 1. Load call data early
            CallRequest call = queueManager.getCallRequest(tenantId, callId);
            if (call == null) {
                log.warn("Call {} has no payload in Redis. Cleaning up.", callId);
                queueManager.dequeue(tenantId, callId);
                continue;
            }

            // 2. Check max retries
            if (retryCount >= MAX_RETRIES) {
                log.warn("Call {} in tenant {} exceeded max retries ({}). Abandoning.",
                    callId, tenantId, MAX_RETRIES);
                queueManager.dequeue(tenantId, callId);
                kafkaMessaging.produceRoutingEvent(
                    AssignmentResult.failure(callId, tenantId, "ABANDONED",
                        "Max retries exceeded. Call abandoned.", call.getTelephonyProvider())
                );
                continue;
            }

            // 3. Check backoff using real timestamps
            long lastRetryAt = queueManager.getLastRetryTime(tenantId, callId);
            long now = System.currentTimeMillis();
            int backoffIndex = (int) Math.min(retryCount, FIBONACCI_BACKOFF_MS.length - 1);
            long requiredBackoffMs = FIBONACCI_BACKOFF_MS[backoffIndex];
            long elapsed = now - lastRetryAt;

            if (lastRetryAt > 0 && elapsed < requiredBackoffMs) {
                log.debug("Call {} backoff not elapsed: elapsed={}ms, required={}ms, retry={}/{}",
                    callId, elapsed, requiredBackoffMs, retryCount, MAX_RETRIES);
                continue;
            }

            log.info("Retrying call {} (retry {}/{}) tenant={} skills={}",
                callId, retryCount, MAX_RETRIES, tenantId, call.getRequiredSkills());

            // 4. Attempt assignment
            AssignmentResult result = routingEngine.assignAgent(call);

            if (result.isSuccess()) {
                log.info("Retry SUCCESS: call {} assigned to agent {} on retry {}",
                    callId, result.getAgentId(), retryCount);
                queueManager.dequeue(tenantId, callId);
                kafkaMessaging.produceRoutingEvent(result);
            } else {
                // Increment retry count + record timestamp
                long newCount = queueManager.incrementRetryCount(tenantId, callId);
                log.info("Retry FAILED: call {} status={} retry={}/{} nextBackoff={}ms",
                    callId, result.getStatus(), newCount, MAX_RETRIES,
                    FIBONACCI_BACKOFF_MS[(int) Math.min(newCount, FIBONACCI_BACKOFF_MS.length - 1)]);

                // If NO_AGENT, stop this tenant's queue — lower priority calls won't match either
                if ("NO_AGENT".equals(result.getStatus())) {
                    break;
                }
            }
        }
    }
}
