package com.minigenesys.telephony.service;

import com.minigenesys.telephony.client.CallServiceClient;
import com.minigenesys.telephony.dto.TelephonyEvent;
import com.minigenesys.telephony.model.TelephonyCallSession;
import com.minigenesys.telephony.repository.TelephonyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelephonyService {
    private final TelephonyRepository repository;
    private final CallServiceClient callServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public String handleInboundCall(String callSid, String from, String to) {
        log.info("Handling inbound call from {} to {} with SID {}", from, to, callSid);
        
        // For now, map all to tenant1 as per requirement
        String tenantId = "tenant1";
        
        // Create internal call
        String internalCallId = callServiceClient.createInternalCall(tenantId, from);
        
        // Store session
        TelephonyCallSession session = TelephonyCallSession.builder()
                .twilioCallSid(callSid)
                .internalCallId(internalCallId)
                .fromNumber(from)
                .toNumber(to)
                .tenantId(tenantId)
                .status("in-progress")
                .build();
        
        repository.save(session);
        
        return internalCallId;
    }

    public void handleStatusCallback(String callSid, String callStatus, String from, String to) {
        log.info("Handling status callback for SID {}: {}", callSid, callStatus);
        
        repository.findByTwilioCallSid(callSid).ifPresent(session -> {
            session.setStatus(callStatus);
            repository.save(session);

            TelephonyEvent event = TelephonyEvent.builder()
                    .eventType("TELEPHONY_STATUS_UPDATE")
                    .callSid(callSid)
                    .callStatus(callStatus)
                    .internalCallId(session.getInternalCallId())
                    .from(from)
                    .to(to)
                    .tenantId(session.getTenantId())
                    .timestamp(System.currentTimeMillis())
                    .build();

            kafkaTemplate.send("telephony-events", session.getTenantId(), event);
        });
    }
}
