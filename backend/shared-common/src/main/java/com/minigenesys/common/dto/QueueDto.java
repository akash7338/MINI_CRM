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
public class QueueDto {
    private String id;
    private String tenantId;
    private String name;
    private Set<String> agentIds;
    private boolean disableSkills;
    private String assignmentType; // "FIFO" | "LIFO"
}
