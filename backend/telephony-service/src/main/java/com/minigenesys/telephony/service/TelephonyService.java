package com.minigenesys.telephony.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.minigenesys.telephony.client.CallServiceClient;
import com.minigenesys.common.dto.RoutingEvent;
import com.minigenesys.common.dto.TelephonyEvent;
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

    public void handleInboundCall(String callSid, String from, String to, String tenantId) {
        log.info("Handling inbound call from {} to {} with SID {} for tenant {}", from, to, callSid, tenantId);

        // 1. Idempotency Check: Don't create a new call if we already have one for this
        // SID
        Optional<TelephonyCallSession> existing = repository.findByTwilioCallSid(callSid);
        if (existing.isPresent()) {
            log.info("Call SID {} already exists, skipping creation.", callSid);
            return;
        }

        // 2. REST call outside @Transactional to avoid holding DB connections
        String internalCallId = callServiceClient.createInternalCall(tenantId, from);

        // 3. Save session in a localized transaction
        saveNewSession(callSid, internalCallId, from, to, tenantId);
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
        if (!"ASSIGNED".equals(event.getStatus()))
            return;

        log.info("Updating telephony session for internal call {} with assigned agent {}",
                event.getCallId(), event.getAgentId());

        Optional<TelephonyCallSession> sessionOpt = repository.findByInternalCallId(event.getCallId());
        if (sessionOpt.isEmpty()) {
            log.info(
                    "Telephony session not found for internal call {}. Skipping assignment handling (probably a FreeSWITCH call).",
                    event.getCallId());
            return;
        }

        TelephonyCallSession session = sessionOpt.get();
        // Only update if not already assigned
        if (session.getAssignedAgentId() == null) {
            session.setAssignedAgentId(event.getAgentId());
            repository.save(session);
            log.info("Successfully saved assigned agent {} to telephony session", event.getAgentId());
        }
    }

    private final Map<String, Map<String, String>> tokenCache = new ConcurrentHashMap<>();
    private final Map<String, Long> tokenExpiry = new ConcurrentHashMap<>();

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
                "identity", identity);

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

            try {
                if ("in-progress".equals(callStatus)) {
                    callServiceClient.startCall(session.getTenantId(), session.getInternalCallId());
                    /*
                     * } else if ("completed".equals(callStatus) || "canceled".equals(callStatus) ||
                     * "no-answer".equals(callStatus) || "failed".equals(callStatus)) {
                     * callServiceClient.completeCall(session.getTenantId(),
                     * session.getInternalCallId());
                     * }
                     */
                } else if ("completed".equals(callStatus)) {
                    callServiceClient.completeCall(session.getTenantId(), session.getInternalCallId());
                }
            } catch (Exception e) {
                log.error("Failed to update call status in CallService: ", e);
            }

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
