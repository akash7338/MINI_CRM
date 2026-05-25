package com.minigenesys.freeswitch.service;

import com.minigenesys.freeswitch.client.CallServiceClient;
import com.minigenesys.freeswitch.model.FreeswitchCallSession;
import com.minigenesys.freeswitch.repository.FreeswitchCallSessionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.IEslEventListener;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 3 & WebRTC integration: ESL inbound listener.
 * Connects to FreeSWITCH Event Socket, subscribes to core channel events,
 * and handles inbound call parking, agent dialing, bridging, and hangup.
 */
@Service
@Slf4j
public class FreeswitchEslService {

    private static final String EVENT_SUBSCRIPTIONS =
            "CHANNEL_CREATE CHANNEL_ANSWER CHANNEL_PARK CHANNEL_BRIDGE CHANNEL_HANGUP_COMPLETE";

    @Value("${freeswitch.esl.host:localhost}")
    private String eslHost;

    @Value("${freeswitch.esl.port:8022}")
    private int eslPort;

    @Value("${freeswitch.esl.password:ClueCon}")
    private String eslPassword;

    @Value("${freeswitch.esl.connect-timeout-seconds:10}")
    private int connectTimeoutSeconds;

    @Value("${freeswitch.esl.retry-interval-seconds:15}")
    private int retryIntervalSeconds;

    @Value("${freeswitch.tenant-id:tenant-freeswitch}")
    private String defaultTenantId;

    @Autowired
    private FreeswitchCallSessionRepository repository;

    @Autowired
    private CallServiceClient callServiceClient;

    private volatile Client client;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "esl-connect-thread");
                t.setDaemon(true);
                return t;
            });

    @PostConstruct
    public void start() {
        running.set(true);
        log.info("Scheduling FreeSWITCH ESL connection attempt to {}:{}", eslHost, eslPort);
        scheduler.schedule(this::connectWithRetry, 0, TimeUnit.SECONDS);
    }

    private void connectWithRetry() {
        if (!running.get()) {
            return;
        }
        log.info("Attempting to connect to FreeSWITCH ESL at {}:{}", eslHost, eslPort);
        try {
            Client newClient = new Client();
            newClient.addEventListener(new IEslEventListener() {
                @Override
                public void eventReceived(EslEvent event) {
                    handleEvent(event);
                }

                @Override
                public void backgroundJobResultReceived(EslEvent event) {
                    log.debug("Background job result: eventName={}", event.getEventName());
                }
            });

            newClient.connect(eslHost, eslPort, eslPassword, connectTimeoutSeconds);
            newClient.setEventSubscriptions("plain", EVENT_SUBSCRIPTIONS);
            client = newClient;
            log.info("Successfully connected to FreeSWITCH ESL and subscribed to events.");
        } catch (Exception e) {
            log.warn("FreeSWITCH ESL connection failed: {}. Retrying in {} seconds.",
                    e.getMessage(), retryIntervalSeconds);
            if (running.get()) {
                scheduler.schedule(this::connectWithRetry, retryIntervalSeconds, TimeUnit.SECONDS);
            }
        }
    }

    public void originateCallToAgent(String agentId, String agentUuid, String customerUuid, String callerId) {
        Client c = client;
        if (c == null) {
            throw new IllegalStateException("FreeSWITCH ESL client is not connected.");
        }
        // Dial format: sofia/internal/sip:{agentId}@localhost or loopback/agent_ans/public for mocks
        // Route directly into conference on answer: &conference(customerUuid@default)
        // Set origination_uuid variable to the pre-generated agentUuid
        // Set origination_caller_id_number and origination_caller_id_name to propagate customer caller ID
        String dialString;
        if (agentId.startsWith("mock_") || "AG-FREESWITCH".equals(agentId)) {
            dialString = "loopback/agent_ans/public";
        } else {
            // Must use %localhost for blind registrations because user/ requires directory xml
            dialString = "sofia/internal/" + agentId + "%localhost";
        }
        String commandArgs = "{origination_uuid=" + agentUuid +
                ",origination_caller_id_number=" + callerId +
                ",origination_caller_id_name=" + callerId +
                ",media_webrtc=true" +
                ",rtp_secure_media=true" +
                ",rtcp_mux=true" +
                "}" + dialString + " &conference(" + customerUuid + "@default)";
        log.info("Originated call to agent {} with callerId {} using command: originate {}", agentId, callerId, commandArgs);
        c.sendAsyncApiCommand("originate", commandArgs);
    }

    public void transferCustomerToConference(String customerUuid) {
        Client c = client;
        if (c == null) {
            throw new IllegalStateException("FreeSWITCH ESL client is not connected.");
        }
        log.info("Transferring customer leg {} to conference room {}@default", customerUuid, customerUuid);
        c.sendAsyncApiCommand("uuid_transfer", customerUuid + " conference:" + customerUuid + "@default inline");
    }

    private void handleEvent(EslEvent event) {
        String eventName = event.getEventName();
        Map<String, String> headers = event.getEventHeaders();

        String uuid        = headers.get("Unique-ID");
        String caller      = headers.get("Caller-Caller-ID-Number");
        String destination = headers.get("Caller-Destination-Number");
        String callState   = headers.get("Channel-Call-State");

        if (caller == null || caller.isEmpty()) {
            caller = headers.get("Caller-ANI");
        }

        log.info("[ESL-EVENT] name={} uuid={} caller={} destination={} callState={}",
                eventName, uuid, caller, destination, callState);

        try {
            if ("CHANNEL_PARK".equals(eventName)) {
                handleChannelPark(uuid, headers, caller);
            } else if ("CHANNEL_ANSWER".equals(eventName)) {
                handleChannelAnswer(uuid, headers);
            } else if ("CHANNEL_HANGUP_COMPLETE".equals(eventName)) {
                handleChannelHangupComplete(uuid);
            }
        } catch (Exception e) {
            log.error("Error processing ESL event {}: {}", eventName, e.getMessage(), e);
        }
    }

    private void handleChannelPark(String uuid, Map<String, String> headers, String caller) {
        String direction = headers.get("Call-Direction");
        log.info("Processing CHANNEL_PARK for uuid: {}, direction: {}", uuid, direction);

        if ("inbound".equalsIgnoreCase(direction)) {
            // Check if already created (idempotency)
            if (repository.existsById(uuid)) {
                log.info("Inbound FreeSWITCH call session already exists for customerUuid: {}", uuid);
                return;
            }

            String tenantId = headers.getOrDefault("variable_tenant_id", defaultTenantId);
            if (tenantId == null || tenantId.isEmpty()) {
                tenantId = defaultTenantId;
            }

            log.info("Creating internal call for inbound FreeSWITCH call. tenantId={}, caller={}", tenantId, caller);
            String internalCallId = callServiceClient.createInternalCall(tenantId, caller);

            FreeswitchCallSession session = FreeswitchCallSession.builder()
                    .customerUuid(uuid)
                    .internalCallId(internalCallId)
                    .tenantId(tenantId)
                    .callerId(caller)
                    .status("PARKED")
                    .build();
            repository.save(session);
            log.info("Created FreeSWITCH call session: customerUuid={}, internalCallId={}", uuid, internalCallId);
        }
    }

    private void handleChannelAnswer(String uuid, Map<String, String> headers) {
        String direction = headers.get("Call-Direction");
        if ("outbound".equalsIgnoreCase(direction)) {
            Optional<FreeswitchCallSession> sessionOpt = repository.findByAgentUuid(uuid);
            if (sessionOpt.isPresent()) {
                FreeswitchCallSession session = sessionOpt.get();
                if ("DIALING_AGENT".equals(session.getStatus())) {
                    log.info("Agent answered WebRTC call. Agent UUID: {} joined conference room: {}",
                            uuid, session.getCustomerUuid());

                    session.setStatus("BRIDGED");
                    repository.save(session);

                    try {
                        callServiceClient.startCall(session.getTenantId(), session.getInternalCallId());
                        log.info("Successfully started call in call-service for internalCallId: {}", session.getInternalCallId());
                    } catch (Exception e) {
                        log.error("Failed to call startCall in call-service: {}", e.getMessage());
                    }
                }
            }
        }
    }

    private void handleChannelHangupComplete(String uuid) {
        log.info("Processing CHANNEL_HANGUP_COMPLETE for uuid: {}", uuid);

        Optional<FreeswitchCallSession> sessionOpt = repository.findById(uuid);
        boolean isCustomer = sessionOpt.isPresent();
        if (!isCustomer) {
            sessionOpt = repository.findByAgentUuid(uuid);
        }

        if (sessionOpt.isPresent()) {
            FreeswitchCallSession session = sessionOpt.get();
            if (!"COMPLETED".equals(session.getStatus())) {
                log.info("Call session hung up: customerUuid={}, agentUuid={}, status={}",
                        session.getCustomerUuid(), session.getAgentUuid(), session.getStatus());

                Client c = client;
                if (c != null) {
                    if (isCustomer && session.getAgentUuid() != null) {
                        log.info("Customer hung up. Terminating agent leg: {}", session.getAgentUuid());
                        c.sendAsyncApiCommand("uuid_kill", session.getAgentUuid());
                    } else if (!isCustomer && session.getCustomerUuid() != null) {
                        log.info("Agent hung up. Terminating customer leg: {}", session.getCustomerUuid());
                        c.sendAsyncApiCommand("uuid_kill", session.getCustomerUuid());
                    }
                }

                session.setStatus("COMPLETED");
                repository.save(session);

                try {
                    callServiceClient.completeCall(session.getTenantId(), session.getInternalCallId());
                    log.info("Successfully completed call in call-service for internalCallId: {}", session.getInternalCallId());
                } catch (Exception e) {
                    log.error("Failed to call completeCall in call-service: {}", e.getMessage());
                }
            }
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
        Client c = client;
        if (c != null) {
            log.info("Shutting down FreeSWITCH ESL connection.");
            try {
                c.close();
            } catch (Exception e) {
                log.warn("Error closing ESL channel: {}", e.getMessage());
            }
        }
    }
}
