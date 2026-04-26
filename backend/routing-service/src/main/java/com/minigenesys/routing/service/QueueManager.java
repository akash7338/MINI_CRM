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

    private static final String QUEUE_KEY_TPL = "tenant:%s:call:queue";
    private static final String CALL_DATA_KEY_TPL = "tenant:%s:call:%s";
    private static final String RETRY_COUNT_KEY_TPL = "tenant:%s:call:%s:retries";
    private static final String LAST_RETRY_KEY_TPL = "tenant:%s:call:%s:lastRetryAt";
    private static final String ACTIVE_TENANTS_KEY = "routing:active-tenants";

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
            long priority = call.getPriority();
            long timestamp = System.currentTimeMillis();
            double score = (double) ((-priority * 10000000000000L) + timestamp);

            redisTemplate.opsForZSet().add(queueKey, callId, score);

            // Register tenant in active-tenants set
            redisTemplate.opsForSet().add(ACTIVE_TENANTS_KEY, tenantId);

            log.info("Enqueued call {} in tenant {} with score {}", callId, tenantId, score);
        } catch (Exception e) {
            log.error("Failed to enqueue call {}: ", callId, e);
        }
    }

    public void dequeue(String tenantId, String callId) {
        String queueKey = String.format(QUEUE_KEY_TPL, tenantId);
        String dataKey = String.format(CALL_DATA_KEY_TPL, tenantId, callId);
        String retryKey = String.format(RETRY_COUNT_KEY_TPL, tenantId, callId);
        String lastRetryKey = String.format(LAST_RETRY_KEY_TPL, tenantId, callId);

        redisTemplate.opsForZSet().remove(queueKey, callId);
        redisTemplate.delete(dataKey);
        redisTemplate.delete(retryKey);
        redisTemplate.delete(lastRetryKey);

        // Clean up tenant from active set if queue is now empty
        Long queueSize = redisTemplate.opsForZSet().zCard(queueKey);
        if (queueSize == null || queueSize == 0) {
            redisTemplate.opsForSet().remove(ACTIVE_TENANTS_KEY, tenantId);
        }
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
        return redisTemplate.opsForSet().members(ACTIVE_TENANTS_KEY);
    }

    /**
     * Increment retry count and record the timestamp of this retry attempt.
     */
    public long incrementRetryCount(String tenantId, String callId) {
        String retryKey = String.format(RETRY_COUNT_KEY_TPL, tenantId, callId);
        String lastRetryKey = String.format(LAST_RETRY_KEY_TPL, tenantId, callId);

        Long count = redisTemplate.opsForValue().increment(retryKey);
        redisTemplate.opsForValue().set(lastRetryKey, String.valueOf(System.currentTimeMillis()));

        return count != null ? count : 1;
    }

    public long getRetryCount(String tenantId, String callId) {
        String retryKey = String.format(RETRY_COUNT_KEY_TPL, tenantId, callId);
        String val = redisTemplate.opsForValue().get(retryKey);
        return val != null ? Long.parseLong(val) : 0;
    }

    /**
     * Returns the epoch millis of the last retry attempt, or 0 if never retried.
     */
    public long getLastRetryTime(String tenantId, String callId) {
        String lastRetryKey = String.format(LAST_RETRY_KEY_TPL, tenantId, callId);
        String val = redisTemplate.opsForValue().get(lastRetryKey);
        return val != null ? Long.parseLong(val) : 0;
    }
}
