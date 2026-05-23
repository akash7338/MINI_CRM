package com.minigenesys.agentstate.repository;

import com.minigenesys.agentstate.model.Queue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface QueueRepository extends JpaRepository<Queue, String> {
    List<Queue> findByTenantId(String tenantId);
    Optional<Queue> findByIdAndTenantId(String id, String tenantId);
}
