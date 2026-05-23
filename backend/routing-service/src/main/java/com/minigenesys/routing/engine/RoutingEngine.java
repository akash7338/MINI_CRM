package com.minigenesys.routing.engine;

import com.minigenesys.routing.dto.AssignmentResult;
import com.minigenesys.routing.dto.CallRequest;
import com.minigenesys.routing.model.Assignment;
import com.minigenesys.routing.repository.AssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoutingEngine {

    private final StringRedisTemplate redisTemplate;
    private final AssignmentRepository assignmentRepository;

    private static final String LOCK_KEY_PREFIX = "routing:lock:call:";
    private static final String IDEMPOTENCY_KEY_PREFIX = "routing:assignment:call:";
    private static final String SKILL_KEY_TPL = "tenant:%s:skill:%s:available";
    private static final String AGENT_STATE_KEY_TPL = "tenant:%s:agent:%s:state";

    private static final String SELECT_AGENT_LUA = 
        "local tenantId = ARGV[1]; " +
        "local agentStateTpl = ARGV[2]; " +
        "local timestamp = ARGV[3]; " +
        "local tempSet = 'temp:routing:' .. ARGV[4]; " +
        "redis.call('ZINTERSTORE', tempSet, #KEYS, unpack(KEYS)); " +
        "local agent = redis.call('ZRANGE', tempSet, 0, 0)[1]; " +
        "redis.call('DEL', tempSet); " +
        "if agent then " +
        "  for i, key in ipairs(KEYS) do redis.call('ZREM', key, agent) end; " +
        "  local stateKey = string.format(agentStateTpl, tenantId, agent); " +
        "  redis.call('HSET', stateKey, 'status', 'BUSY', 'lastAssignedTime', timestamp); " +
        "  return agent; " +
        "end; " +
        "return nil; ";

    public AssignmentResult assignAgent(CallRequest call) {
        String callId = call.getCallId();
        String tenantId = call.getTenantId();
        String lockKey = LOCK_KEY_PREFIX + callId;

        // 1. Redis Lock
        String lockToken = UUID.randomUUID().toString();
        boolean locked = Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, 10, TimeUnit.SECONDS));
        
        if (!locked) {
            return decorate(AssignmentResult.failure(callId, tenantId, "LOCKED", "Routing in progress"), call);
        }

        try {
            // 2. Idempotency Check
            String cachedAgentId = redisTemplate.opsForValue().get(IDEMPOTENCY_KEY_PREFIX + callId);
            if (cachedAgentId != null) {
                String agentStateKey = String.format(AGENT_STATE_KEY_TPL, tenantId, cachedAgentId);
                Object statusObj = redisTemplate.opsForHash().get(agentStateKey, "status");
                String currentStatus = statusObj != null ? statusObj.toString() : null;
                
                if ("BUSY".equals(currentStatus) || "AVAILABLE".equals(currentStatus)) {
                    return decorate(AssignmentResult.success(callId, tenantId, cachedAgentId), call);
                } else {
                    log.warn("Idempotency hit for call {}, but agent {} is offline (status={}). Clearing idempotency cache.", callId, cachedAgentId, currentStatus);
                    redisTemplate.delete(IDEMPOTENCY_KEY_PREFIX + callId);
                }
            }

            // 3. Select Agent via Lua
            List<String> keys = new java.util.ArrayList<>();
            if (call.getQueueId() != null && !call.getQueueId().isEmpty()) {
                keys.add(String.format("tenant:%s:queue:%s:available", tenantId, call.getQueueId()));
            }
            if (!call.isDisableSkills() && call.getRequiredSkills() != null) {
                for (String skill : call.getRequiredSkills()) {
                    keys.add(String.format(SKILL_KEY_TPL, tenantId, skill));
                }
            }
            if (keys.isEmpty()) {
                log.info("No keys to intersect for call {} in tenant {}", callId, tenantId);
                return decorate(AssignmentResult.failure(callId, tenantId, "NO_AGENT", "No queue or skills specified"), call);
            }

            String selectedAgentId = redisTemplate.execute(
                new DefaultRedisScript<>(SELECT_AGENT_LUA, String.class),
                keys,
                tenantId,
                AGENT_STATE_KEY_TPL,
                String.valueOf(Instant.now().toEpochMilli()),
                UUID.randomUUID().toString()
            );

            if (selectedAgentId == null) {
                log.info("No agent available for call {} in tenant {}", callId, tenantId);
                return decorate(AssignmentResult.failure(callId, tenantId, "NO_AGENT", "No available agent matches skills"), call);
            }

            // 4. Persist and Cache Result
            saveAssignment(call, selectedAgentId);

            return decorate(AssignmentResult.success(callId, tenantId, selectedAgentId), call);

        } catch (Exception e) {
            log.error("Error during routing for call {}: ", callId, e);
            return decorate(AssignmentResult.failure(callId, tenantId, "ERROR", e.getMessage()), call);
        } finally {
            String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
            redisTemplate.execute(new DefaultRedisScript<>(script, Long.class), java.util.Collections.singletonList(lockKey), lockToken);
        }
    }

    private AssignmentResult decorate(AssignmentResult result, CallRequest call) {
        if (result != null) {
            result.setTelephonyProvider(call.getTelephonyProvider());
            result.setCampaignId(call.getCampaignId());
            result.setQueueId(call.getQueueId());
        }
        return result;
    }

    private void saveAssignment(CallRequest call, String agentId) {
        Assignment assignment = assignmentRepository.findByCallId(call.getCallId())
                .orElseGet(() -> Assignment.builder().callId(call.getCallId()).build());
        
        assignment.setAgentId(agentId);
        assignment.setTenantId(call.getTenantId());
        assignment.setAssignedAt(Instant.now());
        
        assignmentRepository.save(assignment);
        
        redisTemplate.opsForValue().set(
            IDEMPOTENCY_KEY_PREFIX + call.getCallId(), 
            agentId, 
            1, TimeUnit.HOURS
        );
    }
}
