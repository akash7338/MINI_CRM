package com.minigenesys.callservice.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Set;

@Data
public class CreateCallRequest {
    
    @NotEmpty(message = "requiredSkills cannot be empty")
    private Set<String> requiredSkills;

    private Integer priority;
}
