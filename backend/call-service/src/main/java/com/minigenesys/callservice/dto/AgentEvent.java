package com.minigenesys.callservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {
    private String eventId;
    private String eventType;
    private String agentId;
    private String tenantId;
    private String previousStatus;
    private String newStatus;
    private String callId;
    private Instant timestamp;
}
