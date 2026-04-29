package com.minigenesys.routing.service;

import com.minigenesys.routing.dto.AssignmentResult;
import com.minigenesys.routing.dto.CallRequest;
import com.minigenesys.routing.engine.RoutingEngine;
import com.minigenesys.routing.model.Assignment;
import com.minigenesys.routing.repository.AssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingService {

    private final RoutingEngine routingEngine;
    private final AssignmentRepository assignmentRepository;
    private final QueueManager queueManager;

    public java.util.List<AssignmentResult> processQueue(String tenantId) {
        java.util.List<AssignmentResult> results = new java.util.ArrayList<>();
        java.util.Set<String> queuedCallIds = queueManager.getQueuedCallIds(tenantId);
        if (queuedCallIds == null || queuedCallIds.isEmpty()) {
            return results;
        }

        log.info("Processing queue for tenant {} with {} calls", tenantId, queuedCallIds.size());

        for (String callId : queuedCallIds) {
            CallRequest request = queueManager.getCallRequest(tenantId, callId);
            if (request == null) continue;

            AssignmentResult result = routingEngine.assignAgent(request);
            if ("SUCCESS".equals(result.getStatus())) {
                log.info("Successfully assigned enqueued call {} to agent {}", callId, result.getAgentId());
                queueManager.dequeue(tenantId, callId);
                results.add(result);
            }
        }
        return results;
    }

    public AssignmentResult processRouting(CallRequest request) {
        return routingEngine.assignAgent(request);
    }

    public Optional<Assignment> getAssignment(String callId) {
        return assignmentRepository.findByCallId(callId);
    }
}
