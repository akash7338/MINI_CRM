package com.minigenesys.freeswitch.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class FreeswitchHealthController {

    @GetMapping("/api/v1/freeswitch/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> shortHealth() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
