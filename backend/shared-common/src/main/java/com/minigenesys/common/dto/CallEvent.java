package com.minigenesys.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallEvent {
    private String callId;
    private String tenantId;
    private Set<String> requiredSkills;
    private Integer priority;
    @Builder.Default
    private boolean isNew = true;
}
