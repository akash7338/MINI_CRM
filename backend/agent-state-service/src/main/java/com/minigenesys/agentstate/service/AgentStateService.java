package com.minigenesys.agentstate.service;

import com.minigenesys.agentstate.dto.*;
import com.minigenesys.agentstate.kafka.AgentEventProducer;
import com.minigenesys.agentstate.model.Agent;
import com.minigenesys.agentstate.model.AgentStatus;
import com.minigenesys.agentstate.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.minigenesys.agentstate.dto.CallLifecycleEvent;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStateService {

    private final AgentRepository agentRepository;
    private final StringRedisTemplate redisTemplate;
    private final AgentEventProducer agentEventProducer;

    private static final String AGENT_STATE_KEY_TPL = "tenant:%s:agent:%s:state";
    private static final String SKILL_KEY_TPL = "tenant:%s:skill:%s:available";

    @Transactional
    public AgentStateResponse changeState(String tenantId, String agentId, AgentStatus newStatus) {
        Agent agent = agentRepository.findByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found"));

        AgentStatus oldStatus = agent.getStatus();

        if (!isValidTransition(oldStatus, newStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid state transition from " + oldStatus + " to " + newStatus);
        }

        agent.setStatus(newStatus);
        if (newStatus == AgentStatus.BUSY) {
            agent.setLastAssignedTime(Instant.now().toEpochMilli());
        }

        agent = agentRepository.save(agent);
        updateRedisState(agent, oldStatus, newStatus);
        publishEvent(agent, oldStatus, newStatus);

        return mapToResponse(agent);
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
        
        // Update DB
        agent.setStatus(AgentStatus.BUSY);
        agent.setLastAssignedTime(Instant.now().toEpochMilli());
        agentRepository.save(agent);

        // Update Redis (Ensuring consistency with routing-service direct mutation)
        updateRedisState(agent, oldStatus, AgentStatus.BUSY);
        
        // Publish agent-events
        publishEvent(agent, oldStatus, AgentStatus.BUSY);
        
        log.info("Agent {} state synchronized to BUSY", agent.getId());
    }

    @Transactional
    public void handleCallCompletion(CallLifecycleEvent event) {
        log.info("Processing call completion for agent: {} in tenant: {}", event.getAgentId(), event.getTenantId());

        Optional<Agent> agentOpt = agentRepository.findByIdAndTenantId(event.getAgentId(), event.getTenantId());
        if (agentOpt.isEmpty()) {
            log.warn("Agent {} not found in tenant {} for call completion update", event.getAgentId(), event.getTenantId());
            return;
        }

        Agent agent = agentOpt.get();
        AgentStatus oldStatus = agent.getStatus();

        // Update DB
        agent.setStatus(AgentStatus.AVAILABLE);
        agentRepository.save(agent);

        // Update Redis (Release agent)
        updateRedisState(agent, oldStatus, AgentStatus.AVAILABLE);

        // Publish agent-events
        publishEvent(agent, oldStatus, AgentStatus.AVAILABLE);

        log.info("Agent {} state synchronized back to AVAILABLE after call completion", agent.getId());
    }

    private boolean isValidTransition(AgentStatus oldStatus, AgentStatus newStatus) {
        if (oldStatus == newStatus) return false;
        if (oldStatus == AgentStatus.OFFLINE && newStatus == AgentStatus.AVAILABLE) return true;
        if (oldStatus == AgentStatus.AVAILABLE && newStatus == AgentStatus.BUSY) return true;
        if (oldStatus == AgentStatus.BUSY && newStatus == AgentStatus.AVAILABLE) return true;
        if (oldStatus == AgentStatus.AVAILABLE && newStatus == AgentStatus.OFFLINE) return true;
        if (oldStatus == AgentStatus.BUSY && newStatus == AgentStatus.OFFLINE) return true;
        return false;
    }

    private void updateRedisState(Agent agent, AgentStatus oldStatus, AgentStatus newStatus) {
        String stateKey = String.format(AGENT_STATE_KEY_TPL, agent.getTenantId(), agent.getId());
        
        // Always update the state hash
        redisTemplate.opsForHash().put(stateKey, "status", newStatus.name());
        if (agent.getLastAssignedTime() != null) {
            redisTemplate.opsForHash().put(stateKey, "lastAssignedTime", String.valueOf(agent.getLastAssignedTime()));
        }
        
        if (newStatus == AgentStatus.AVAILABLE) {
            long score = agent.getLastAssignedTime() != null ? agent.getLastAssignedTime() : Instant.now().toEpochMilli();
            for (String skill : agent.getSkills()) {
                String skillKey = String.format(SKILL_KEY_TPL, agent.getTenantId(), skill);
                redisTemplate.opsForZSet().add(skillKey, agent.getId(), score);
            }
        } else if (newStatus == AgentStatus.BUSY || newStatus == AgentStatus.OFFLINE) {
            for (String skill : agent.getSkills()) {
                String skillKey = String.format(SKILL_KEY_TPL, agent.getTenantId(), skill);
                redisTemplate.opsForZSet().remove(skillKey, agent.getId());
            }
        }
        
        if (newStatus == AgentStatus.OFFLINE) {
             redisTemplate.delete(stateKey);
        }
    }

    private void publishEvent(Agent agent, AgentStatus oldStatus, AgentStatus newStatus) {
        AgentEvent event = AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("AGENT_" + newStatus.name())
                .agentId(agent.getId())
                .tenantId(agent.getTenantId())
                .previousStatus(oldStatus != null ? oldStatus.name() : null)
                .newStatus(newStatus.name())
                .timestamp(Instant.now())
                .build();
        agentEventProducer.publishAgentEvent(event);
    }

    private AgentStateResponse mapToResponse(Agent agent) {
        return AgentStateResponse.builder()
                .agentId(agent.getId())
                .tenantId(agent.getTenantId())
                .status(agent.getStatus())
                .lastAssignedTime(agent.getLastAssignedTime())
                .build();
    }
}
