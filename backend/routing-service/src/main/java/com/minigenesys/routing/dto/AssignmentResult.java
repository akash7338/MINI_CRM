package com.minigenesys.routing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentResult {
    private String callId;
    private String tenantId;
    private String agentId;
    private String status;
    private boolean success;
    private String message;

    public static AssignmentResult success(String callId, String tenantId, String agentId) {
        return AssignmentResult.builder()
                .callId(callId)
                .tenantId(tenantId)
                .agentId(agentId)
                .status("ASSIGNED")
                .success(true)
                .build();
    }

    public static AssignmentResult failure(String callId, String tenantId, String status, String message) {
        return AssignmentResult.builder()
                .callId(callId)
                .tenantId(tenantId)
                .status(status)
                .success(false)
                .message(message)
                .build();
    }
}
