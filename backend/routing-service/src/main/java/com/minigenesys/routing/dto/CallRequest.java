package com.minigenesys.routing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CallRequest {
    @NotBlank(message = "tenantId is mandatory")
    private String tenantId;

    @NotBlank(message = "callId is mandatory")
    private String callId;

    @NotEmpty(message = "requiredSkills cannot be empty")
    private Set<String> requiredSkills;

    private long priority;

    private String telephonyProvider;
}
