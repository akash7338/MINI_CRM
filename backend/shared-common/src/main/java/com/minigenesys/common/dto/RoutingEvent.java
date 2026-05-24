package com.minigenesys.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingEvent {
    private String callId;
    private String agentId;
    private String tenantId;
    private String status;
    private String message;
    private String telephonyProvider;
}
