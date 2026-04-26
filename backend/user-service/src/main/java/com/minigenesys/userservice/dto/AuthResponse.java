package com.minigenesys.userservice.dto;

import com.minigenesys.userservice.model.Role;
import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data
@Builder
public class AuthResponse {
    private String accessToken;
    private UUID userId;
    private String tenantId;
    private Role role;
    private String agentId;
}
