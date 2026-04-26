package com.minigenesys.userservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateSupervisorRequest {
    @NotBlank
    private String username;
    @NotBlank
    private String password;
    @NotBlank
    private String tenantId;
}
