package com.minigenesys.diagnostics.service;

import com.minigenesys.diagnostics.config.DiagnosticsConfig.DiagnosticsProperties;
import com.minigenesys.diagnostics.config.DiagnosticsConfig.DiagnosticsProperties.ServiceEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthAggregatorService {

    private final WebClient webClient;
    private final DiagnosticsProperties properties;
    private final InfrastructureHealthService infraHealthService;

    @SuppressWarnings("unchecked")
    public Map<String, Object> aggregateHealth() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean allUp = true;

        Map<String, Object> infraHealth = infraHealthService.checkAll();
        result.put("infrastructure", infraHealth);
        for (Object v : infraHealth.values()) {
            if (v instanceof Map && !"UP".equals(((Map<String, String>) v).get("status"))) {
                allUp = false;
            }
        }

        Map<String, Object> servicesHealth = new LinkedHashMap<>();
        if (properties.getServices() != null) {
            for (Map.Entry<String, ServiceEntry> entry : properties.getServices().entrySet()) {
                String serviceName = entry.getKey();
                ServiceEntry svc = entry.getValue();
                Map<String, Object> health = callServiceHealth(serviceName, svc);
                servicesHealth.put(serviceName, health);
                if (!"UP".equals(health.get("status"))) {
                    allUp = false;
                }
            }
        }
        result.put("services", servicesHealth);

        result.put("overall", allUp ? "UP" : "DEGRADED");
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> publicHealthSummary() {
        Map<String, Object> full = aggregateHealth();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("overall", full.getOrDefault("overall", "UNKNOWN"));

        Map<String, Object> infraStatuses = new LinkedHashMap<>();
        Map<String, Object> serviceStatuses = new LinkedHashMap<>();

        int servicesUp = 0;
        int servicesDown = 0;
        int infraUp = 0;
        int infraDown = 0;

        Object infraObj = full.get("infrastructure");
        if (infraObj instanceof Map<?, ?> infra) {
            for (Map.Entry<?, ?> entry : infra.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String status = "UNKNOWN";
                if (entry.getValue() instanceof Map<?, ?> component) {
                    Object s = component.get("status");
                    if (s != null) {
                        status = String.valueOf(s);
                    }
                }
                infraStatuses.put(key, status);
                if ("UP".equals(status)) {
                    infraUp++;
                } else {
                    infraDown++;
                }
            }
        }

        Object servicesObj = full.get("services");
        if (servicesObj instanceof Map<?, ?> services) {
            for (Map.Entry<?, ?> entry : services.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String status = "UNKNOWN";
                if (entry.getValue() instanceof Map<?, ?> svc) {
                    Object s = svc.get("status");
                    if (s != null) {
                        status = String.valueOf(s);
                    }
                }
                serviceStatuses.put(key, status);
                if ("UP".equals(status)) {
                    servicesUp++;
                } else {
                    servicesDown++;
                }
            }
        }

        summary.put("infrastructure", infraStatuses);
        summary.put("services", serviceStatuses);
        summary.put("counts", Map.of(
                "servicesUp", servicesUp,
                "servicesDown", servicesDown,
                "infraUp", infraUp,
                "infraDown", infraDown
        ));
        return summary;
    }

    private Map<String, Object> callServiceHealth(String name, ServiceEntry svc) {
        try {
            Map<?, ?> body = webClient.get()
                    .uri(svc.getUrl() + svc.getHealthPath())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(3))
                    .onErrorResume(e -> Mono.just(Map.of("status", "DOWN", "error", e.getMessage())))
                    .block();

            Map<String, Object> healthResult = new LinkedHashMap<>();
            if (body != null) {
                body.forEach((k, v) -> healthResult.put(String.valueOf(k), v));
            }
            if (!healthResult.containsKey("status")) {
                healthResult.put("status", "UNKNOWN");
            }
            return healthResult;
        } catch (Exception e) {
            log.warn("Health check failed for {}: {}", name, e.getMessage());
            return Map.of("status", "DOWN", "error", e.getMessage());
        }
    }
}
