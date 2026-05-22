package com.minigenesys.userservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tenant is the source of truth for telephony provider selection.
 * Every agent (User) belongs to a tenant via tenantId.
 * The telephonyProvider field determines which SDK/backend handles calls for this tenant.
 *
 * telephonyProvider values: "TWILIO" | "FREESWITCH"
 */
@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @Column(nullable = false, updatable = false)
    private String id;

    @Column(nullable = false)
    private String name;

    /**
     * Telephony provider for all agents in this tenant.
     * Must be explicitly set. Valid values: "TWILIO", "FREESWITCH".
     */
    @Column(name = "telephony_provider", nullable = false)
    private String telephonyProvider;
}
