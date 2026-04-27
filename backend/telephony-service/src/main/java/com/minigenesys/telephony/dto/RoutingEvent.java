package com.minigenesys.telephony.dto;

import lombok.Data;

@Data
public class RoutingEvent {
    private String callId;
    private String tenantId;
    private String agentId;
    private String status;
    private boolean success;
    private String message;
}
