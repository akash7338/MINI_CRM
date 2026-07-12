package com.minigenesys.freeswitch.service;

import com.minigenesys.common.dto.RoutingEvent;
import com.minigenesys.common.dto.OutboundCallEvent;
import com.minigenesys.freeswitch.model.FreeswitchCallSession;
import com.minigenesys.freeswitch.repository.FreeswitchCallSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FreeswitchCallService {

    private final FreeswitchCallSessionRepository repository;
    private final FreeswitchEslService eslService;

    @Transactional
    public void handleAssignment(RoutingEvent event) {
        // Skip events for other providers. Null is treated as TWILIO (legacy/default), which is not FREESWITCH.
        if (!"FREESWITCH".equals(event.getTelephonyProvider())) {
            log.debug("Skipping non-FREESWITCH routing event for call {} with provider {}", event.getCallId(), event.getTelephonyProvider());
            return;
        }

        if (!"ASSIGNED".equals(event.getStatus())) {
            return;
        }

        log.info("Processing agent assignment for FreeSWITCH service: callId={}, agentId={}",
                event.getCallId(), event.getAgentId());

        Optional<FreeswitchCallSession> sessionOpt = repository.findByInternalCallId(event.getCallId());
        if (sessionOpt.isEmpty()) {
            throw new IllegalStateException("FreeSWITCH session not found for internal call: " + event.getCallId());
        }

        FreeswitchCallSession session = sessionOpt.get();
        if (session.getAssignedAgentId() != null) {
            log.info("Call session {} is already assigned to agent {}", session.getCustomerUuid(), session.getAssignedAgentId());
            return;
        }

        String agentUuid = UUID.randomUUID().toString();
        session.setAssignedAgentId(event.getAgentId());
        session.setAgentUuid(agentUuid);
        session.setStatus("DIALING_AGENT");
        repository.save(session);

        log.info("Transferring customer leg {} to conference room", session.getCustomerUuid());
        try {
            eslService.transferCustomerToConference(session.getCustomerUuid());
        } catch (Exception e) {
            log.error("Failed to transfer customer {} to conference: {}", session.getCustomerUuid(), e.getMessage());
        }

        log.info("Dialing WebRTC agent {} with agentUuid {} for customerUuid {}", event.getAgentId(), agentUuid, session.getCustomerUuid());
        try {
            eslService.originateCallToAgent(event.getAgentId(), agentUuid, session.getCustomerUuid(), session.getCallerId());
        } catch (Exception e) {
            log.error("Failed to originate call to agent {}: {}", event.getAgentId(), e.getMessage(), e);
        }
    }

    @Transactional
    public void handleOutboundCall(OutboundCallEvent event) {
        log.info("Processing outbound call: callId={}, agentId={}, toNumber={}", 
                event.getCallId(), event.getAgentId(), event.getToNumber());

        // Generate UUIDs for agent and customer legs
        String conferenceUuid = event.getCallId(); // Use callId as conference room identifier
        String agentUuid = UUID.randomUUID().toString();
        String customerUuid = UUID.randomUUID().toString();

        // Create session for outbound call
        FreeswitchCallSession session = FreeswitchCallSession.builder()
                .customerUuid(customerUuid)
                .internalCallId(event.getCallId())
                .tenantId(event.getTenantId())
                .callerId(event.getCallerId())
                .toNumber(event.getToNumber())
                .direction("OUTBOUND")
                .assignedAgentId(event.getAgentId())
                .agentUuid(agentUuid)
                .status("DIALING_AGENT")
                .build();
        
        repository.save(session);
        log.info("Created outbound FreeSWITCH session: customerUuid={}, agentUuid={}, callId={}", 
                customerUuid, agentUuid, event.getCallId());

        // Originate the outbound call (agent + customer)
        try {
            eslService.originateOutboundCall(
                event.getAgentId(), 
                event.getToNumber(), 
                event.getCallerId(),
                conferenceUuid,
                agentUuid,
                customerUuid
            );
            log.info("Successfully originated outbound call for callId: {}", event.getCallId());
        } catch (Exception e) {
            log.error("Failed to originate outbound call for callId {}: {}", event.getCallId(), e.getMessage(), e);
        }
    }
}
