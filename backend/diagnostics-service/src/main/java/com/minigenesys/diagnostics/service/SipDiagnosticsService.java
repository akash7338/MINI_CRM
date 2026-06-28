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
public class SipDiagnosticsService {

    private final WebClient webClient;
    private final DiagnosticsProperties properties;

    private String getFreeswitchBaseUrl() {
        if (properties.getServices() == null) return "http://localhost:8093";
        ServiceEntry fs = properties.getServices().get("freeswitch-service");
        return fs != null ? fs.getUrl() : "http://localhost:8093";
    }

    public Map<String, Object> getSipDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        String baseUrl = getFreeswitchBaseUrl();

        result.put("sofiaStatus", fetchDiagnostic(baseUrl + "/api/v1/freeswitch/diagnostics/sofia-status"));
        result.put("gatewayStatus", fetchDiagnostic(baseUrl + "/api/v1/freeswitch/diagnostics/gateway-status"));
        result.put("externalProfile", fetchDiagnostic(baseUrl + "/api/v1/freeswitch/diagnostics/profile/external"));
        result.put("internalProfile", fetchDiagnostic(baseUrl + "/api/v1/freeswitch/diagnostics/profile/internal"));

        return result;
    }

    public Map<String, Object> getActiveChannels() {
        String baseUrl = getFreeswitchBaseUrl();
        Object channels = fetchDiagnostic(baseUrl + "/api/v1/freeswitch/diagnostics/active-channels");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channels", channels);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object fetchDiagnostic(String url) {
        try {
            return webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(5))
                    .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())))
                    .block();
        } catch (Exception e) {
            log.warn("SIP diagnostics fetch failed from {}: {}", url, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
}
