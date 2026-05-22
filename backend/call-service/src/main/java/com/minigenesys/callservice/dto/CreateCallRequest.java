package com.minigenesys.callservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Set;

@Data
public class CreateCallRequest {

    @NotEmpty(message = "requiredSkills cannot be empty")
    private Set<String> requiredSkills;

    private String callerId;

    private Integer priority;

    /**
     * Must be set explicitly by the caller: "TWILIO" or "FREESWITCH".
     * Derived from the tenant's configured telephonyProvider before this call is created.
     * telephony-service sets "TWILIO"; freeswitch-service sets "FREESWITCH".
     */
    @NotBlank(message = "telephonyProvider is required")
    private String telephonyProvider;
}
