package com.minigenesys.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboundCallEvent {
    private String callId;
    private String tenantId;
    private String agentId;
    private String toNumber;
    private String callerId;
    private String telephonyProvider; // "TWILIO" | "FREESWITCH"
}
