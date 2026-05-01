package com.minigenesys.telephony.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CallServiceClient {
    private final RestTemplate restTemplate;

    @Value("${services.callServiceUrl}")
    private String callServiceUrl;

    public String createInternalCall(String tenantId, String fromNumber) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenantId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "callerId", fromNumber,
                "requiredSkills", List.of("sales"),
                "priority", 1);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
                callServiceUrl + "/api/v1/calls",
                entity,
                Map.class);

        return (String) response.get("id");
    }

    public void startCall(String tenantId, String callId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenantId);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        restTemplate.postForObject(callServiceUrl + "/api/v1/calls/" + callId + "/start", entity, Void.class);
    }

    public void completeCall(String tenantId, String callId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenantId);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        restTemplate.postForObject(callServiceUrl + "/api/v1/calls/" + callId + "/complete", entity, Void.class);
    }
}
