package com.minigenesys.callservice.dto;

import com.minigenesys.callservice.model.CallStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallResponse {
    private String id;
    private String tenantId;
    private String callerId;
    private Set<String> requiredSkills;
    private Integer priority;
    private CallStatus status;
    private String assignedAgentId;
    private String routingFailureReason;
    private Instant createdAt;
    private Instant updatedAt;
}
