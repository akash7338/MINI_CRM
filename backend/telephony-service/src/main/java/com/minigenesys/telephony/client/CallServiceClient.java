package com.minigenesys.telephony.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CallServiceClient {
    private final RestTemplate restTemplate;

    @Value("${services.callServiceUrl}")
    private String callServiceUrl;

    public String createInternalCall(String tenantId, String fromNumber) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Tenant-Id", tenantId);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "callerId", fromNumber,
            "requiredSkills", List.of("sales"),
            "priority", 1
        );

        org.springframework.http.HttpEntity<Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(body, headers);

        Map<String, Object> response = restTemplate.postForObject(
            callServiceUrl + "/api/v1/calls",
            entity,
            Map.class
        );

        return (String) response.get("id");
    }

    public void startCall(String tenantId, String callId) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Tenant-Id", tenantId);
        org.springframework.http.HttpEntity<Void> entity = new org.springframework.http.HttpEntity<>(headers);
        restTemplate.postForObject(callServiceUrl + "/api/v1/calls/" + callId + "/start", entity, Void.class);
    }

    public void completeCall(String tenantId, String callId) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Tenant-Id", tenantId);
        org.springframework.http.HttpEntity<Void> entity = new org.springframework.http.HttpEntity<>(headers);
        restTemplate.postForObject(callServiceUrl + "/api/v1/calls/" + callId + "/complete", entity, Void.class);
    }
}
