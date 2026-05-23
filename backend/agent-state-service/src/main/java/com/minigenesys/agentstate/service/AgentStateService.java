package com.minigenesys.agentstate.service;

import com.minigenesys.agentstate.dto.*;
import com.minigenesys.common.dto.*;
import com.minigenesys.agentstate.kafka.AgentEventProducer;
import com.minigenesys.agentstate.model.Agent;
import com.minigenesys.agentstate.model.AgentStatus;
import com.minigenesys.agentstate.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.minigenesys.common.dto.CallLifecycleEvent;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStateService {

    private final AgentRepository agentRepository;
    private final StringRedisTemplate redisTemplate;
    private final AgentEventProducer agentEventProducer;

    private static final String AGENT_STATE_KEY_TPL = "tenant:%s:agent:%s:state";
    private static final String SKILL_KEY_TPL = "tenant:%s:skill:%s:available";
    private static final String HEARTBEAT_KEY_TPL = "tenant:%s:agent:%s:heartbeat";

    private static final long HEARTBEAT_TIMEOUT_MS = 30000;

    @Transactional
    public AgentStateResponse createAgent(String tenantId, CreateAgentRequest request) {
        if (agentRepository.existsById(request.getAgentId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent ID already exists");
        }

        Agent agent = Agent.builder()
                .id(request.getAgentId())
                .tenantId(tenantId)
                .name(request.getName())
                .skills(request.getSkills())
                .status(AgentStatus.OFFLINE)
                .build();

        agent = agentRepository.save(agent);
        return mapToResponse(agent);
    }

    @Transactional
    public AgentStateResponse changeState(String tenantId, String agentId, AgentStatus newStatus) {
        Agent agent = agentRepository.findByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found"));

        AgentStatus oldStatus = agent.getStatus();
        if (oldStatus == newStatus) {
            return mapToResponse(agent);
        }

        if (!isValidTransition(oldStatus, newStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Invalid state transition from " + oldStatus + " to " + newStatus);
        }

        agent.setStatus(newStatus);
        if (newStatus == AgentStatus.BUSY) {
            agent.setLastAssignedTime(Instant.now().toEpochMilli());
        } else if (newStatus == AgentStatus.OFFLINE) {
            agent.setActiveCallId(null);
        }

        agent = agentRepository.save(agent);
        updateRedisState(agent, newStatus);
        publishEvent(agent, oldStatus, newStatus, "AGENT_" + newStatus.name());

        return mapToResponse(agent);
    }

    @Transactional
    public void handleHeartbeat(String tenantId, String agentId) {
        Agent agent = agentRepository.findByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found"));

        if (agent.getStatus() == AgentStatus.OFFLINE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent is OFFLINE. Please login first.");
        }

        long now = Instant.now().toEpochMilli();
        agent.setLastHeartbeatAt(now);
        agentRepository.save(agent);

        String heartbeatKey = String.format(HEARTBEAT_KEY_TPL, tenantId, agentId);
        redisTemplate.opsForValue().set(heartbeatKey, String.valueOf(now), HEARTBEAT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        log.debug("Heartbeat received for agent {} in tenant {}", agentId, tenantId);
    }

    @Scheduled(fixedRateString = "${agent.heartbeat.scan-interval:10000}")
    @Transactional
    public void detectDisconnects() {
        long threshold = Instant.now().toEpochMilli() - HEARTBEAT_TIMEOUT_MS;
        List<AgentStatus> activeStatuses = List.of(AgentStatus.AVAILABLE, AgentStatus.BUSY);

        List<Agent> expiredAgents = agentRepository.findByStatusInAndLastHeartbeatAtBefore(activeStatuses, threshold);
        // Also check agents who never sent a heartbeat but are active (if any)
        List<Agent> neverHeartbeatAgents = agentRepository.findByStatusInAndLastHeartbeatAtIsNull(activeStatuses);

        expiredAgents.addAll(neverHeartbeatAgents);

        if (expiredAgents.isEmpty())
            return;

        log.info("Detected {} disconnected agents", expiredAgents.size());

        for (Agent agent : expiredAgents) {
            AgentStatus oldStatus = agent.getStatus();
            if (oldStatus == AgentStatus.BUSY && agent.getActiveCallId() != null) {
                log.info("Agent {} was BUSY with call {}. Disconnecting.", agent.getId(), agent.getActiveCallId());
            }

            agent.setStatus(AgentStatus.OFFLINE);
            agent.setActiveCallId(null);
            agentRepository.save(agent);

            updateRedisState(agent, AgentStatus.OFFLINE);
            publishEvent(agent, oldStatus, AgentStatus.OFFLINE, "AGENT_DISCONNECTED");

            // Explicitly delete heartbeat key
            redisTemplate.delete(String.format(HEARTBEAT_KEY_TPL, agent.getTenantId(), agent.getId()));

            log.info("Agent {} marked OFFLINE due to heartbeat timeout", agent.getId());
        }
    }

    @Transactional(readOnly = true)
    public AgentStateResponse getState(String tenantId, String agentId) {
        Agent agent = agentRepository.findByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found"));
        return mapToResponse(agent);
    }

    @Transactional
    public void handleRoutingEvent(RoutingEvent event) {
        if (!"ASSIGNED".equals(event.getStatus()) || event.getAgentId() == null) {
            return;
        }

        log.info("Processing routing assignment for agent: {} in tenant: {}", event.getAgentId(), event.getTenantId());

        Optional<Agent> agentOpt = agentRepository.findByIdAndTenantId(event.getAgentId(), event.getTenantId());
        if (agentOpt.isEmpty()) {
            log.warn("Agent {} not found in tenant {} for routing update", event.getAgentId(), event.getTenantId());
            return;
        }

        Agent agent = agentOpt.get();
        AgentStatus oldStatus = agent.getStatus();

        if (oldStatus == AgentStatus.OFFLINE) {
            log.warn("Ignoring routing event for agent {} because they are OFFLINE. Breaking the ping-pong loop.",
                    agent.getId());
            return;
        }

        // Update DB
        agent.setStatus(AgentStatus.BUSY);
        agent.setActiveCallId(event.getCallId());
        agent.setLastAssignedTime(Instant.now().toEpochMilli());
        agentRepository.save(agent);

        // Update Redis (Ensuring consistency with routing-service direct mutation)
        updateRedisState(agent, AgentStatus.BUSY);

        // Publish agent-events
        publishEvent(agent, oldStatus, AgentStatus.BUSY, "AGENT_BUSY");

        log.info("Agent {} state synchronized to BUSY", agent.getId());
    }

    @Transactional
    public void handleCallCompletion(CallLifecycleEvent event) {
        log.info("Processing call completion for agent: {} in tenant: {}", event.getAgentId(), event.getTenantId());

        Optional<Agent> agentOpt = agentRepository.findByIdAndTenantId(event.getAgentId(), event.getTenantId());
        if (agentOpt.isEmpty()) {
            log.warn("Agent {} not found in tenant {} for call completion update", event.getAgentId(),
                    event.getTenantId());
            return;
        }

        Agent agent = agentOpt.get();
        AgentStatus oldStatus = agent.getStatus();

        // Update DB
        agent.setStatus(AgentStatus.AVAILABLE);
        agent.setActiveCallId(null);
        agentRepository.save(agent);

        // Update Redis (Release agent)
        updateRedisState(agent, AgentStatus.AVAILABLE);

        // Publish agent-events
        publishEvent(agent, oldStatus, AgentStatus.AVAILABLE, "AGENT_AVAILABLE");

        log.info("Agent {} state synchronized back to AVAILABLE after call completion", agent.getId());
    }

    private boolean isValidTransition(AgentStatus oldStatus, AgentStatus newStatus) {
        if (oldStatus == newStatus)
            return false;
        if (oldStatus == AgentStatus.OFFLINE && newStatus == AgentStatus.AVAILABLE)
            return true;
        if (oldStatus == AgentStatus.AVAILABLE && newStatus == AgentStatus.BUSY)
            return true;
        if (oldStatus == AgentStatus.BUSY && newStatus == AgentStatus.AVAILABLE)
            return true;
        if (oldStatus == AgentStatus.AVAILABLE && newStatus == AgentStatus.OFFLINE)
            return true;
        // Allows forced logout during active calls (e.g., agent rejects a call)
        if (oldStatus == AgentStatus.BUSY && newStatus == AgentStatus.OFFLINE)
            return true;
        return false;
    }

    private void updateRedisState(Agent agent, AgentStatus newStatus) {
        String stateKey = String.format(AGENT_STATE_KEY_TPL, agent.getTenantId(), agent.getId());

        // Always update the state hash
        redisTemplate.opsForHash().put(stateKey, "status", newStatus.name());
        if (agent.getLastAssignedTime() != null) {
            redisTemplate.opsForHash().put(stateKey, "lastAssignedTime", String.valueOf(agent.getLastAssignedTime()));
        }

        if (newStatus == AgentStatus.AVAILABLE) {
            long score = agent.getLastAssignedTime() != null ? agent.getLastAssignedTime()
                    : Instant.now().toEpochMilli();
            for (String skill : agent.getSkills()) {
                String skillKey = String.format(SKILL_KEY_TPL, agent.getTenantId(), skill);
                redisTemplate.opsForZSet().add(skillKey, agent.getId(), score);
            }
            if (agent.getQueueIds() != null) {
                for (String queueId : agent.getQueueIds()) {
                    String queueKey = String.format("tenant:%s:queue:%s:available", agent.getTenantId(), queueId);
                    redisTemplate.opsForZSet().add(queueKey, agent.getId(), score);
                }
            }
        } else if (newStatus == AgentStatus.BUSY || newStatus == AgentStatus.OFFLINE) {
            for (String skill : agent.getSkills()) {
                String skillKey = String.format(SKILL_KEY_TPL, agent.getTenantId(), skill);
                redisTemplate.opsForZSet().remove(skillKey, agent.getId());
            }
            if (agent.getQueueIds() != null) {
                for (String queueId : agent.getQueueIds()) {
                    String queueKey = String.format("tenant:%s:queue:%s:available", agent.getTenantId(), queueId);
                    redisTemplate.opsForZSet().remove(queueKey, agent.getId());
                }
            }
        }

        if (newStatus == AgentStatus.OFFLINE) {
            redisTemplate.delete(stateKey);
        }
    }

    private void publishEvent(Agent agent, AgentStatus oldStatus, AgentStatus newStatus, String eventType) {
        AgentEvent event = AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .agentId(agent.getId())
                .tenantId(agent.getTenantId())
                .previousStatus(oldStatus != null ? oldStatus.name() : null)
                .newStatus(newStatus.name())
                .callId(agent.getActiveCallId())
                .timestamp(Instant.now())
                .build();
        agentEventProducer.publishAgentEvent(event);
    }

    public Map<String, Long> getCounts(String tenantId) {
        return Map.of(
                "AVAILABLE", agentRepository.countByTenantIdAndStatus(tenantId, AgentStatus.AVAILABLE),
                "BUSY", agentRepository.countByTenantIdAndStatus(tenantId, AgentStatus.BUSY),
                "OFFLINE", agentRepository.countByTenantIdAndStatus(tenantId, AgentStatus.OFFLINE));
    }

    private AgentStateResponse mapToResponse(Agent agent) {
        return AgentStateResponse.builder()
                .agentId(agent.getId())
                .tenantId(agent.getTenantId())
                .status(agent.getStatus())
                .activeCallId(agent.getActiveCallId())
                .lastAssignedTime(agent.getLastAssignedTime())
                .build();
    }
}
