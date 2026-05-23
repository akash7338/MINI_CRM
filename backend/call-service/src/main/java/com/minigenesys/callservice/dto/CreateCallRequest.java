package com.minigenesys.callservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Set;

@Data
public class CreateCallRequest {

    private Set<String> requiredSkills;

    private String callerId;

    private Integer priority;

    private String campaignId;

    private String queueId;

    /**
     * Must be set explicitly by the caller: "TWILIO" or "FREESWITCH".
     * Derived from the tenant's configured telephonyProvider before this call is
     * created.
     * telephony-service sets "TWILIO"; freeswitch-service sets "FREESWITCH".
     */
    @NotBlank(message = "telephonyProvider is required")
    private String telephonyProvider;
}
