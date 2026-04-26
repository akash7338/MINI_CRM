package com.minigenesys.agentstate.repository;

import com.minigenesys.agentstate.model.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentRepository extends JpaRepository<Agent, String> {
    Optional<Agent> findByIdAndTenantId(String id, String tenantId);
    
    java.util.List<Agent> findByStatusInAndLastHeartbeatAtBefore(java.util.Collection<com.minigenesys.agentstate.model.AgentStatus> statuses, Long timestamp);
    
    java.util.List<Agent> findByStatusInAndLastHeartbeatAtIsNull(java.util.Collection<com.minigenesys.agentstate.model.AgentStatus> statuses);
}
