package com.minigenesys.routing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.routing.dto.CallRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueManager {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String QUEUE_KEY_TPL = "tenant:%s:queue";
    private static final String CALL_DATA_KEY_TPL = "tenant:%s:call:%s";

    public void enqueue(CallRequest call) {
        String tenantId = call.getTenantId();
        String callId = call.getCallId();
        String queueKey = String.format(QUEUE_KEY_TPL, tenantId);
        String dataKey = String.format(CALL_DATA_KEY_TPL, tenantId, callId);

        try {
            // Store call data
            String payload = objectMapper.writeValueAsString(call);
            redisTemplate.opsForValue().set(dataKey, payload);

            // Calculate score: (-priority * 10^13) + timestamp
            // priority is usually 1, 2, 3... higher is better
            long priority = call.getPriority();
            long timestamp = System.currentTimeMillis();
            double score = (double) ((-priority * 10000000000000L) + timestamp);

            redisTemplate.opsForZSet().add(queueKey, callId, score);
            log.info("Enqueued call {} in tenant {} with score {}", callId, tenantId, score);
        } catch (Exception e) {
            log.error("Failed to enqueue call {}: ", callId, e);
        }
    }

    public void dequeue(String tenantId, String callId) {
        String queueKey = String.format(QUEUE_KEY_TPL, tenantId);
        String dataKey = String.format(CALL_DATA_KEY_TPL, tenantId, callId);
        redisTemplate.opsForZSet().remove(queueKey, callId);
        redisTemplate.delete(dataKey);
    }

    public CallRequest getCallRequest(String tenantId, String callId) {
        String dataKey = String.format(CALL_DATA_KEY_TPL, tenantId, callId);
        String payload = redisTemplate.opsForValue().get(dataKey);
        if (payload == null) return null;
        try {
            return objectMapper.readValue(payload, CallRequest.class);
        } catch (Exception e) {
            log.error("Failed to deserialize call request {}: ", callId, e);
            return null;
        }
    }

    public Set<String> getQueuedCallIds(String tenantId) {
        String queueKey = String.format(QUEUE_KEY_TPL, tenantId);
        return redisTemplate.opsForZSet().range(queueKey, 0, -1);
    }

    public Set<String> getAllTenantsWithQueues() {
        // This is a bit expensive if we use keys, but for now we'll look for tenant:*:queue
        // In production, we'd maintain a set of active tenants
        return redisTemplate.keys("tenant:*:queue");
    }
}
