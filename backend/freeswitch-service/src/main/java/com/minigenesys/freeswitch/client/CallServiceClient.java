package com.minigenesys.freeswitch.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;

@Component
@RequiredArgsConstructor
@Slf4j
public class CallServiceClient {
    private final RestTemplate restTemplate;

    @Value("${services.callServiceUrl}")
    private String callServiceUrl;

    @Value("#{'${freeswitch.default-skills:sales}'.split(',')}")
    private List<String> defaultSkills;

    public String createInternalCall(String tenantId, String fromNumber, String campaignId, String queueId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenantId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("callerId", fromNumber);
        body.put("telephonyProvider", "FREESWITCH");
        if (campaignId != null) {
            body.put("campaignId", campaignId);
        }
        if (queueId != null) {
            body.put("queueId", queueId);
        } else {
            body.put("requiredSkills", defaultSkills);
            body.put("priority", 1);
        }

        HttpEntity<java.util.Map<String, Object>> entity = new HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> response = restTemplate.postForObject(
                callServiceUrl + "/api/v1/calls",
                entity,
                java.util.Map.class);

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

    public com.minigenesys.common.dto.CampaignDto getCampaignByDid(String tenantId, String did) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Tenant-Id", tenantId);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<com.minigenesys.common.dto.CampaignDto> response = restTemplate.exchange(
                    callServiceUrl + "/api/v1/campaigns/by-did/" + did,
                    HttpMethod.GET,
                    entity,
                    com.minigenesys.common.dto.CampaignDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Campaign not found for DID {} in tenant {}: {}", did, tenantId, e.getMessage());
            return null;
        }
    }
}
