package com.minigenesys.telephony.service;

import java.util.Map;
import java.util.Optional;

import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.minigenesys.telephony.client.CallServiceClient;
import com.minigenesys.telephony.dto.RoutingEvent;
import com.minigenesys.telephony.dto.TelephonyEvent;
import com.minigenesys.telephony.model.TelephonyCallSession;
import com.minigenesys.telephony.repository.TelephonyRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelephonyService {
    private final TelephonyRepository repository;
    private final CallServiceClient callServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${twilio.accountSid}")
    private String accountSid;

    @Value("${twilio.apiKeySid}")
    private String apiKeySid;

    @Value("${twilio.apiKeySecret}")
    private String apiKeySecret;

    @Value("${twilio.twimlAppSid}")
    private String twimlAppSid;

    public String handleInboundCall(String callSid, String from, String to) {
        log.info("Handling inbound call from {} to {} with SID {}", from, to, callSid);
        
        // 1. Idempotency Check: Don't create a new call if we already have one for this SID
        Optional<TelephonyCallSession> existing = repository.findByTwilioCallSid(callSid);
        if (existing.isPresent()) {
            log.info("Call SID {} already exists, returning existing internal call ID", callSid);
            return existing.get().getInternalCallId();
        }

        // 2. REST call outside @Transactional to avoid holding DB connections
        String tenantId = "tenant1"; // TODO: Lookup tenant by 'To' number
        String internalCallId = callServiceClient.createInternalCall(tenantId, from);
        
        // 3. Save session in a localized transaction
        saveNewSession(callSid, internalCallId, from, to, tenantId);
        
        return internalCallId;
    }

    @Transactional
    protected void saveNewSession(String callSid, String internalCallId, String from, String to, String tenantId) {
        TelephonyCallSession session = TelephonyCallSession.builder()
                .twilioCallSid(callSid)
                .internalCallId(internalCallId)
                .fromNumber(from)
                .toNumber(to)
                .tenantId(tenantId)
                .status("ringing")
                .build();
        repository.save(session);
    }

    @Transactional
    public void handleAssignment(RoutingEvent event) {
        if (!"ASSIGNED".equals(event.getStatus())) return;
        
        log.info("Updating telephony session for internal call {} with assigned agent {}", 
            event.getCallId(), event.getAgentId());
            
        repository.findByInternalCallId(event.getCallId()).ifPresent(session -> {
            // Only update if not already assigned
            if (session.getAssignedAgentId() == null) {
                session.setAssignedAgentId(event.getAgentId());
                repository.save(session);
            }
        });
    }

    private final Map<String, Map<String, String>> tokenCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> tokenExpiry = new java.util.concurrent.ConcurrentHashMap<>();

    public Map<String, String> generateToken(String identity) {
        long now = System.currentTimeMillis();
        if (tokenCache.containsKey(identity) && now < tokenExpiry.get(identity)) {
            return tokenCache.get(identity);
        }

        log.info("Generating token for identity: {}", identity);

        VoiceGrant grant = new VoiceGrant();
        grant.setIncomingAllow(true);
        grant.setOutgoingApplicationSid(twimlAppSid);

        AccessToken token = new AccessToken.Builder(accountSid, apiKeySid, apiKeySecret)
                .identity(identity)
                .grant(grant)
                .build();

        Map<String, String> result = Map.of(
            "token", token.toJwt(),
            "identity", identity
        );

        tokenCache.put(identity, result);
        tokenExpiry.put(identity, now + 5000); // Cache for 5 seconds

        return result;
    }


    public String getBridgeTwiml(String callSid) {
        Optional<TelephonyCallSession> sessionOpt = repository.findByTwilioCallSid(callSid);
        
        if (sessionOpt.isPresent() && sessionOpt.get().getAssignedAgentId() != null) {
            String agentId = sessionOpt.get().getAssignedAgentId();
            log.info("Bridging call {} to agent {}", callSid, agentId);
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                   "<Response>\n" +
                   "    <Dial answerOnBridge=\"true\">\n" +
                   "        <Client>" + agentId + "</Client>\n" +
                   "    </Dial>\n" +
                   "</Response>";
        }

        // Still waiting
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<Response>\n" +
               "    <Say>Your call is still in queue</Say>\n" +
               "    <Pause length=\"3\"/>\n" +
               "    <Redirect method=\"GET\">/api/v1/telephony/twilio/bridge?callSid=" + callSid + "</Redirect>\n" +
               "</Response>";
    }

    @Transactional
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

            // Note: In a full production app, we would use a Transactional Outbox here
            kafkaTemplate.send("telephony-events", session.getTenantId(), event);
        });
    }
}
