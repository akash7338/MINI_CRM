package com.minigenesys.agentstate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallLifecycleEvent {
    private String eventType;
    private String callId;
    private String tenantId;
    private String agentId;
}
