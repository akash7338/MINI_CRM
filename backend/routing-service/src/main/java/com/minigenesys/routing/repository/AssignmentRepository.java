package com.minigenesys.routing.repository;

import com.minigenesys.routing.model.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, String> {
    Optional<Assignment> findByCallId(String callId);
}
