package com.minigenesys.diagnostics.controller;

import com.minigenesys.diagnostics.service.HealthAggregatorService;
import com.minigenesys.diagnostics.service.LogReaderService;
import com.minigenesys.diagnostics.service.LogStreamService;
import com.minigenesys.diagnostics.service.ServiceControlService;
import com.minigenesys.diagnostics.service.SipDiagnosticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/diagnostics")
@RequiredArgsConstructor
public class DiagnosticsController {

    private final HealthAggregatorService healthAggregatorService;
    private final SipDiagnosticsService sipDiagnosticsService;
    private final LogReaderService logReaderService;
    private final LogStreamService logStreamService;
    private final ServiceControlService serviceControlService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(healthAggregatorService.aggregateHealth());
    }

    @GetMapping("/public/healthz")
    public ResponseEntity<Map<String, Object>> publicHealth() {
        return ResponseEntity.ok(healthAggregatorService.publicHealthSummary());
    }

    @GetMapping("/sip")
    public ResponseEntity<Map<String, Object>> sipDiagnostics() {
        return ResponseEntity.ok(sipDiagnosticsService.getSipDiagnostics());
    }

    @GetMapping("/calls")
    public ResponseEntity<Map<String, Object>> activeCalls() {
        return ResponseEntity.ok(sipDiagnosticsService.getActiveChannels());
    }

    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> logs(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "100") int lines) {
        if (service == null || service.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "service parameter is required"));
        }
        return ResponseEntity.ok(logReaderService.readLogs(service, level, lines));
    }

    @GetMapping("/logs/services")
    public ResponseEntity<List<Map<String, Object>>> logServices() {
        return ResponseEntity.ok(logReaderService.listLogFiles());
    }

    @GetMapping(value = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(
            @RequestParam String service,
            @RequestParam(required = false) String level) {
        return logStreamService.stream(service, level);
    }

    @PostMapping("/services/{name}/stop")
    public ResponseEntity<Map<String, Object>> stopService(@PathVariable String name) {
        if (!serviceControlService.isControllable(name)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Unknown or non-controllable service: " + name));
        }
        return ResponseEntity.ok(serviceControlService.stop(name));
    }

    @PostMapping("/services/{name}/start")
    public ResponseEntity<Map<String, Object>> startService(@PathVariable String name) {
        if (!serviceControlService.isControllable(name)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Unknown or non-controllable service: " + name));
        }
        return ResponseEntity.ok(serviceControlService.start(name));
    }

    @PostMapping("/services/{name}/restart")
    public ResponseEntity<Map<String, Object>> restartService(@PathVariable String name) {
        if (!serviceControlService.isControllable(name)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Unknown or non-controllable service: " + name));
        }
        return ResponseEntity.ok(serviceControlService.restart(name));
    }
}
