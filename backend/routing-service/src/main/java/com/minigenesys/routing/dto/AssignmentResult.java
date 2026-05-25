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
    private String telephonyProvider;

    public static AssignmentResult success(String callId, String tenantId, String agentId, String telephonyProvider) {
        return AssignmentResult.builder()
                .callId(callId)
                .tenantId(tenantId)
                .agentId(agentId)
                .status("ASSIGNED")
                .success(true)
                .telephonyProvider(telephonyProvider)
                .build();
    }

    public static AssignmentResult failure(String callId, String tenantId, String status, String message, String telephonyProvider) {
        return AssignmentResult.builder()
                .callId(callId)
                .tenantId(tenantId)
                .status(status)
                .success(false)
                .message(message)
                .telephonyProvider(telephonyProvider)
                .build();
    }
}
