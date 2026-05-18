package com.minigenesys.agentstate.repository;

import com.minigenesys.agentstate.model.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.minigenesys.agentstate.model.AgentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentRepository extends JpaRepository<Agent, String> {
    Optional<Agent> findByIdAndTenantId(String id, String tenantId);

    List<Agent> findByStatusInAndLastHeartbeatAtBefore(
            Collection<AgentStatus> statuses, Long timestamp);

    List<Agent> findByStatusInAndLastHeartbeatAtIsNull(
            Collection<AgentStatus> statuses);

    long countByTenantIdAndStatus(String tenantId, AgentStatus status);
}
