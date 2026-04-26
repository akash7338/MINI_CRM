package com.minigenesys.userservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Set;

@Data
public class CreateAgentRequest {
    @NotBlank
    private String username;
    @NotBlank
    private String password;
    @NotBlank
    private String agentId;
    @NotBlank
    private String name;
    
    private Set<String> skills;
}
