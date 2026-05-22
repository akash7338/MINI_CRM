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

    /**
     * Convenience field for the frontend — derived from Tenant.telephonyProvider.
     * Tells the frontend which SDK to initialize (TWILIO or FREESWITCH).
     * Source of truth remains the tenants table, not this field.
     */
    private String telephonyProvider;
}
