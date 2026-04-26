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
    private final com.minigenesys.routing.service.QueueManager queueManager;

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
        boolean locked = Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "LOCKED", 10, TimeUnit.SECONDS));
        
        if (!locked) {
            return AssignmentResult.failure(callId, tenantId, "LOCKED", "Routing in progress");
        }

        try {
            // 2. Idempotency Check
            String cachedAgentId = redisTemplate.opsForValue().get(IDEMPOTENCY_KEY_PREFIX + callId);
            if (cachedAgentId != null) {
                return AssignmentResult.success(callId, tenantId, cachedAgentId);
            }

            // 3. Select Agent via Lua
            List<String> skillKeys = call.getRequiredSkills().stream()
                    .map(skill -> String.format(SKILL_KEY_TPL, tenantId, skill))
                    .collect(Collectors.toList());

            String selectedAgentId = redisTemplate.execute(
                new DefaultRedisScript<>(SELECT_AGENT_LUA, String.class),
                skillKeys,
                tenantId,
                AGENT_STATE_KEY_TPL,
                String.valueOf(Instant.now().toEpochMilli()),
                UUID.randomUUID().toString()
            );

            if (selectedAgentId == null) {
                log.info("No agent available for call {} in tenant {}. Enqueuing.", callId, tenantId);
                queueManager.enqueue(call);
                return AssignmentResult.failure(callId, tenantId, "NO_AGENT", "No available agent matches skills. Call enqueued.");
            }

            // 4. Persist and Cache Result
            saveAssignment(call, selectedAgentId);

            return AssignmentResult.success(callId, tenantId, selectedAgentId);

        } catch (Exception e) {
            log.error("Error during routing for call {}: ", callId, e);
            return AssignmentResult.failure(callId, tenantId, "ERROR", e.getMessage());
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private void saveAssignment(CallRequest call, String agentId) {
        Assignment assignment = Assignment.builder()
                .callId(call.getCallId())
                .agentId(agentId)
                .tenantId(call.getTenantId())
                .assignedAt(Instant.now())
                .build();
        
        assignmentRepository.save(assignment);
        
        redisTemplate.opsForValue().set(
            IDEMPOTENCY_KEY_PREFIX + call.getCallId(), 
            agentId, 
            1, TimeUnit.HOURS
        );
    }
}
