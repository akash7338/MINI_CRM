package com.minigenesys.websocket.controller;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/websocket")
public class HealthController {

    @Autowired(required = false)
    private KafkaAdmin kafkaAdmin;

    private final Instant startTime = Instant.now();

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "websocket-gateway");

        Map<String, Object> components = new LinkedHashMap<>();
        boolean allUp = true;

        if (kafkaAdmin != null) {
            try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
                admin.listTopics(new ListTopicsOptions().timeoutMs(2000)).names().get(3, TimeUnit.SECONDS);
                components.put("kafka", Map.of("status", "UP"));
            } catch (Exception e) {
                components.put("kafka", Map.of("status", "DOWN", "error", e.getMessage()));
                allUp = false;
            }
        }

        response.put("status", allUp ? "UP" : "DOWN");
        response.put("components", components);
        response.put("uptime", formatUptime(Duration.between(startTime, Instant.now())));

        return ResponseEntity.ok(response);
    }

    private String formatUptime(Duration d) {
        long hours = d.toHours();
        int minutes = d.toMinutesPart();
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m " + d.toSecondsPart() + "s";
    }
}
