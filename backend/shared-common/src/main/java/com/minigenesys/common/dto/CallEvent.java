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
public class CallEvent {
    private String callId;
    private String tenantId;
    private Set<String> requiredSkills;
    private Integer priority;
    @Builder.Default
    private boolean newCall = true;

    /**
     * Telephony provider for this call, derived from Tenant.telephonyProvider.
     * Values: "TWILIO" | "FREESWITCH".
     *
     * NOTE: May be null for old Kafka messages already in-flight before this field
     * was added. Consumers must treat null as "TWILIO" ONLY during the migration
     * window. New calls must always set this explicitly.
     */
    private String telephonyProvider;
}
