package com.minigenesys.callservice.service;

import com.minigenesys.callservice.dto.*;
import com.minigenesys.common.dto.*;
import com.minigenesys.callservice.kafka.CallEventProducer;
import com.minigenesys.callservice.model.Call;
import com.minigenesys.callservice.model.CallStatus;
import com.minigenesys.callservice.repository.CallRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import com.minigenesys.common.dto.CallLifecycleEvent;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallService {

    private final CallRepository callRepository;
    private final CallEventProducer callEventProducer;

    @Transactional
    public CallResponse createCall(String tenantId, CreateCallRequest request) {
        Integer priority = request.getPriority() != null ? request.getPriority() : 1;

        Call call = Call.builder()
                .tenantId(tenantId)
                .callerId(request.getCallerId())
                .requiredSkills(request.getRequiredSkills())
                .priority(priority)
                .status(CallStatus.QUEUED) // Initial status per requirements
                .telephonyProvider(request.getTelephonyProvider()) // explicit, never defaulted
                .build();

        call = callRepository.save(call);

        CallEvent event = CallEvent.builder()
                .callId(call.getId())
                .tenantId(call.getTenantId())
                .requiredSkills(call.getRequiredSkills())
                .priority(call.getPriority())
                .telephonyProvider(call.getTelephonyProvider()) // carry through to routing-service
                .build();

        callEventProducer.publishCallEvent(event);

        return mapToResponse(call);
    }

    @Transactional(readOnly = true)
    public CallResponse getCall(String callId, String tenantId) {
        Call call = callRepository.findById(callId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found"));

        if (!call.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return mapToResponse(call);
    }

    @Transactional
    public CallResponse startCall(String callId, String tenantId) {
        Call call = callRepository.findById(callId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found"));

        if (!call.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (call.getStatus() != CallStatus.ROUTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Call must be in ROUTED status to start");
        }

        call.setStatus(CallStatus.IN_PROGRESS);
        call = callRepository.save(call);
        return mapToResponse(call);
    }

    @Transactional
    public CallResponse completeCall(String callId, String tenantId) {
        Call call = callRepository.findById(callId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found"));

        if (!call.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        /*
        if (call.getStatus() != CallStatus.IN_PROGRESS && 
            call.getStatus() != CallStatus.ROUTED && 
            call.getStatus() != CallStatus.QUEUED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Call must be in IN_PROGRESS, ROUTED, or QUEUED status to complete");
        }
        */
        if (call.getStatus() == CallStatus.COMPLETED || call.getStatus() == CallStatus.FAILED || call.getStatus() == CallStatus.ABANDONED) {
            log.info("Call {} is already in terminal status: {}. Ignoring complete request.", callId, call.getStatus());
            return mapToResponse(call);
        }

        if (call.getStatus() != CallStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Call must be in IN_PROGRESS status to complete");
        }

        call.setStatus(CallStatus.COMPLETED);
        call = callRepository.save(call);

        CallLifecycleEvent event = CallLifecycleEvent.builder()
                .eventType("CALL_COMPLETED")
                .callId(call.getId())
                .tenantId(call.getTenantId())
                .agentId(call.getAssignedAgentId())
                .build();

        callEventProducer.publishLifecycleEvent(event);

        return mapToResponse(call);
    }

    @Transactional
    public CallResponse rejectCall(String callId, String tenantId) {
        Call call = callRepository.findById(callId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call not found"));

        if (!call.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (call.getStatus() != CallStatus.ROUTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Call must be in ROUTED status to reject");
        }

        String rejectedAgentId = call.getAssignedAgentId();

        // 1. Requeue the call
        call.setStatus(CallStatus.QUEUED);
        call.setAssignedAgentId(null);
        call = callRepository.save(call);

        // 2. Publish CALL_REQUEUED (same as new call event)
        CallEvent requeueEvent = CallEvent.builder()
                .callId(call.getId())
                .tenantId(call.getTenantId())
                .requiredSkills(call.getRequiredSkills())
                .priority(call.getPriority())
                .newCall(false)
                .telephonyProvider(call.getTelephonyProvider()) // provider never changes after creation
                .build();
        callEventProducer.publishCallEvent(requeueEvent);

        // 3. Free the agent who rejected the call
        if (rejectedAgentId != null) {
            CallLifecycleEvent event = CallLifecycleEvent.builder()
                    .eventType("CALL_COMPLETED")
                    .callId(call.getId())
                    .tenantId(call.getTenantId())
                    .agentId(rejectedAgentId)
                    .build();
            callEventProducer.publishLifecycleEvent(event);
        }

        return mapToResponse(call);
    }

    @Transactional
    public void handleRoutingEvent(RoutingEvent event) {
        log.info("Handling routing event for callId: {}, status: {}", event.getCallId(), event.getStatus());
        
        Call call = callRepository.findById(event.getCallId())
                .orElseGet(() -> {
                    log.warn("Call not found for ID: {}", event.getCallId());
                    return null;
                });

        if (call == null) return;

        // Safety check for tenantId if present in event
        if (event.getTenantId() != null && !call.getTenantId().equals(event.getTenantId())) {
            log.error("Tenant mismatch for callId: {}. Event tenant: {}, DB tenant: {}", 
                event.getCallId(), event.getTenantId(), call.getTenantId());
            return;
        }

        String status = event.getStatus();
        if ("ASSIGNED".equals(status)) {
            call.setStatus(CallStatus.ROUTED);
            call.setAssignedAgentId(event.getAgentId());
            call.setRoutingFailureReason(null);
        } else if ("NO_AGENT".equals(status)) {
            call.setStatus(CallStatus.QUEUED);
            call.setRoutingFailureReason(event.getMessage());
        } else if ("ERROR".equals(status) || "failed".equalsIgnoreCase(status)) {
            call.setStatus(CallStatus.FAILED);
            call.setRoutingFailureReason(event.getMessage());
        } else if ("ABANDONED".equals(status)) {
            call.setStatus(CallStatus.ABANDONED);
            call.setRoutingFailureReason(event.getMessage());
            
            // If the call was previously routed, free the agent
            if (call.getAssignedAgentId() != null) {
                CallLifecycleEvent abandonEvent = CallLifecycleEvent.builder()
                        .eventType("CALL_COMPLETED") // Forces agent back to AVAILABLE
                        .callId(call.getId())
                        .tenantId(call.getTenantId())
                        .agentId(call.getAssignedAgentId())
                        .build();
                callEventProducer.publishLifecycleEvent(abandonEvent);
            }
        }

        callRepository.save(call);
        log.info("Updated callId: {} to status: {}", call.getId(), call.getStatus());
    }

    @Transactional
    public void handleAgentDisconnect(AgentEvent event) {
        if (!"AGENT_DISCONNECTED".equals(event.getEventType())) {
            return;
        }

        log.info("Handling disconnect for agent: {} in tenant: {}. Searching for active calls to recover.", 
            event.getAgentId(), event.getTenantId());

        List<CallStatus> activeStatuses = Arrays.asList(CallStatus.ROUTED, CallStatus.IN_PROGRESS);
        List<Call> activeCalls = callRepository.findByAssignedAgentIdAndStatusIn(event.getAgentId(), activeStatuses);

        if (activeCalls.isEmpty()) {
            log.info("No active calls found for disconnected agent: {}", event.getAgentId());
            return;
        }

        for (Call call : activeCalls) {
            // Idempotency: skip if already requeued/handled by a previous delivery of this event
            if (call.getStatus() == CallStatus.QUEUED || call.getStatus() == CallStatus.FAILED) {
                log.info("Call {} already in status {}. Skipping duplicate handling for agent {}.", 
                    call.getId(), call.getStatus(), event.getAgentId());
                continue;
            }

            if (call.getStatus() == CallStatus.IN_PROGRESS) {
                log.info("Call {} was active/bridged (IN_PROGRESS) when agent {} disconnected. " +
                        "Skipping retry/requeue because the real telephony leg is no longer alive. " +
                        "Marking call as FAILED and cleaning up agent state.", call.getId(), event.getAgentId());

                call.setStatus(CallStatus.FAILED);
                call.setRoutingFailureReason("Agent disconnected during active call.");
                callRepository.save(call);

                // Publish CALL_COMPLETED to clean up the agent state
                CallLifecycleEvent agentCleanupEvent = CallLifecycleEvent.builder()
                        .eventType("CALL_COMPLETED")
                        .callId(call.getId())
                        .tenantId(call.getTenantId())
                        .agentId(event.getAgentId())
                        .build();
                callEventProducer.publishLifecycleEvent(agentCleanupEvent);
                continue;
            }

            log.info("Recovering pre-answer call: {} from disconnected agent: {}. Requeuing.", 
                call.getId(), event.getAgentId());

            call.setStatus(CallStatus.QUEUED);
            call.setAssignedAgentId(null);
            callRepository.save(call);

            // Publish CALL_REQUEUED (same as initial call event but for routing-service to pick it up)
            CallEvent requeueEvent = CallEvent.builder()
                    .callId(call.getId())
                    .tenantId(call.getTenantId())
                    .requiredSkills(call.getRequiredSkills())
                    .priority(call.getPriority())
                    .newCall(false)
                    .telephonyProvider(call.getTelephonyProvider()) // provider never changes after creation
                    .build();

            callEventProducer.publishCallEvent(requeueEvent);
            log.info("Call {} requeued and published to Kafka", call.getId());
        }
    }

    private CallResponse mapToResponse(Call call) {
        return CallResponse.builder()
                .id(call.getId())
                .tenantId(call.getTenantId())
                .callerId(call.getCallerId())
                .requiredSkills(call.getRequiredSkills())
                .priority(call.getPriority())
                .status(call.getStatus())
                .assignedAgentId(call.getAssignedAgentId())
                .routingFailureReason(call.getRoutingFailureReason())
                .telephonyProvider(call.getTelephonyProvider())
                .createdAt(call.getCreatedAt())
                .updatedAt(call.getUpdatedAt())
                .build();
    }
}
