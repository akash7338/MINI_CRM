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
public class CampaignDto {
    private String id;
    private String tenantId;
    private String name;
    private Set<String> dids;
    private String queueId;
    private String holdPrompt;
    private Integer recordingPercentage;
    private String callerId;
}
