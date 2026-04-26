package com.minigenesys.analytics.controller;

import com.minigenesys.analytics.model.TenantMetrics;
import com.minigenesys.analytics.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/{tenantId}/metrics")
    public ResponseEntity<TenantMetrics> getMetrics(@PathVariable String tenantId) {
        return ResponseEntity.ok(analyticsService.getMetrics(tenantId));
    }

    @PostMapping("/{tenantId}/sync")
    public ResponseEntity<Void> sync(@PathVariable String tenantId) {
        analyticsService.syncAgentCounts(tenantId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "analytics-service"));
    }
}
