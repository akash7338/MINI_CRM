package com.minigenesys.agentstate.dto;

import com.minigenesys.agentstate.model.AgentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStateResponse {
    private String agentId;
    private String tenantId;
    private AgentStatus status;
    private Long lastAssignedTime;
}
