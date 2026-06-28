package com.minigenesys.userservice.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    @Autowired
    private DataSource dataSource;

    private final Instant startTime = Instant.now();

    @GetMapping("/api/v1/users/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "user-service");

        Map<String, Object> components = new LinkedHashMap<>();
        boolean allUp = true;

        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(2);
            components.put("database", Map.of("status", valid ? "UP" : "DOWN"));
            if (!valid) allUp = false;
        } catch (Exception e) {
            components.put("database", Map.of("status", "DOWN", "error", e.getMessage()));
            allUp = false;
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
