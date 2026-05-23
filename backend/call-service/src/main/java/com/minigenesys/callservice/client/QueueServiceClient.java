package com.minigenesys.callservice.client;

import com.minigenesys.common.dto.QueueDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueueServiceClient {
    private final RestTemplate restTemplate;
    
    public QueueDto getQueue(String tenantId, String queueId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Tenant-Id", tenantId);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            
            ResponseEntity<QueueDto> response = restTemplate.exchange(
                "http://localhost:8086/api/v1/queues/" + queueId,
                HttpMethod.GET,
                entity,
                QueueDto.class
            );
            
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to fetch queue details for queueId {} in tenant {}: {}", queueId, tenantId, e.getMessage());
            return null;
        }
    }
}
