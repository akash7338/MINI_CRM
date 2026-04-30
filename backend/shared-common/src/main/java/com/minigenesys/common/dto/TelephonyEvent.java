package com.minigenesys.common.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TelephonyEvent {
    private String eventType;
    private String callSid;
    private String callStatus;
    private String internalCallId;
    private String from;
    private String to;
    private String tenantId;
    private long timestamp;
}
