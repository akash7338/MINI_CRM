package com.minigenesys.freeswitch.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.IEslEventListener;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 3: ESL inbound listener.
 * Connects to FreeSWITCH Event Socket, subscribes to the five core channel events,
 * and logs them. No call control or call-service integration yet (Phase 4+).
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
        // Attempt immediately, then retry if it fails
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

    private void handleEvent(EslEvent event) {
        String eventName = event.getEventName();
        Map<String, String> headers = event.getEventHeaders();

        String uuid        = headers.get("Unique-ID");
        String caller      = headers.get("Caller-Caller-ID-Number");
        String destination = headers.get("Caller-Destination-Number");
        String callState   = headers.get("Channel-Call-State");

        // Caller-ANI is a valid secondary field (not Caller-Screen-Bit which is a boolean flag)
        if (caller == null || caller.isEmpty()) {
            caller = headers.get("Caller-ANI");
        }

        log.info("[ESL-EVENT] name={} uuid={} caller={} destination={} callState={}",
                eventName, uuid, caller, destination, callState);
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
