package com.minigenesys.callservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Set;

@Data
public class CreateOutboundCallRequest {

    /**
     * Destination phone number in E.164 format (e.g., +14155551234).
     */
    @NotBlank(message = "toNumber is required")
    @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$", message = "toNumber must be in E.164 format")
    private String toNumber;

    /**
     * Agent ID initiating the outbound call.
     */
    @NotBlank(message = "agentId is required")
    private String agentId;

    /**
     * Optional caller ID to display to the customer.
     * If not provided, system will use default outbound number.
     */
    private String callerId;

    /**
     * Skills required for this call (can be empty for manual dials).
     */
    private Set<String> requiredSkills;

    /**
     * Priority (defaults to 1 if not provided).
     */
    private Integer priority;

    /**
     * Telephony provider: "TWILIO" or "FREESWITCH".
     * Must be set explicitly based on tenant configuration.
     */
    @NotBlank(message = "telephonyProvider is required")
    private String telephonyProvider;
}
