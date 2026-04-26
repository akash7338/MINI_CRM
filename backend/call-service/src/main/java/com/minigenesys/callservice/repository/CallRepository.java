package com.minigenesys.callservice.repository;

import com.minigenesys.callservice.model.Call;
import com.minigenesys.callservice.model.CallStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CallRepository extends JpaRepository<Call, String> {
    List<Call> findByAssignedAgentIdAndStatusIn(String agentId, Collection<CallStatus> statuses);
}
