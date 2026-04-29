package com.minigenesys.routing.service;

import com.minigenesys.routing.dto.AssignmentResult;
import com.minigenesys.routing.dto.CallRequest;
import com.minigenesys.routing.engine.RoutingEngine;
import com.minigenesys.routing.model.Assignment;
import com.minigenesys.routing.repository.AssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RoutingService {

    private final RoutingEngine routingEngine;
    private final AssignmentRepository assignmentRepository;

    public AssignmentResult processRouting(CallRequest request) {
        return routingEngine.assignAgent(request);
    }

    public Optional<Assignment> getAssignment(String callId) {
        return assignmentRepository.findByCallId(callId);
    }
}
