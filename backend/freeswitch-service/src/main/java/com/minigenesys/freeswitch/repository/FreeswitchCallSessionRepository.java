package com.minigenesys.freeswitch.repository;

import com.minigenesys.freeswitch.model.FreeswitchCallSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface FreeswitchCallSessionRepository extends JpaRepository<FreeswitchCallSession, String> {
    Optional<FreeswitchCallSession> findByInternalCallId(String internalCallId);
    Optional<FreeswitchCallSession> findByAgentUuid(String agentUuid);
}
