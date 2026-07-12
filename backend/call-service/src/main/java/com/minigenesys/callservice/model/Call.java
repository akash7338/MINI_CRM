package com.minigenesys.callservice.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Set;

@Entity
@Table(name = "calls")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Call {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    private String callerId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "call_skills", joinColumns = @JoinColumn(name = "call_id"))
    @Column(name = "skill", nullable = false)
    private Set<String> requiredSkills;

    @Column(nullable = false)
    private Integer priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CallStatus status;

    private String assignedAgentId;

    private String routingFailureReason;

    /**
     * Telephony provider that owns this call. Derived from Tenant.telephonyProvider
     * at call creation time and never changed afterwards.
     * Values: "TWILIO" | "FREESWITCH".
     *
     * columnDefinition DEFAULT 'TWILIO': existing rows created before this column
     * was added will be backfilled with TWILIO by ddl-auto:update ALTER TABLE.
     * All new calls must supply this value explicitly via CreateCallRequest.
     */
    @Column(name = "telephony_provider", nullable = false,
            columnDefinition = "VARCHAR(32) DEFAULT 'TWILIO'")
    private String telephonyProvider;

    /**
     * Direction of the call.
     * Values: "INBOUND" | "OUTBOUND".
     * Default is INBOUND for backward compatibility.
     */
    @Column(name = "direction", nullable = false,
            columnDefinition = "VARCHAR(10) DEFAULT 'INBOUND'")
    private String direction;

    /**
     * Destination phone number for outbound calls (E.164 format).
     * Null for inbound calls.
     */
    @Column(name = "to_number")
    private String toNumber;

    /**
     * Call disposition/outcome recorded after call completion.
     * Values: "ANSWERED" | "NO_ANSWER" | "BUSY" | "VOICEMAIL" | "FAILED".
     */
    @Column(name = "disposition")
    private String disposition;

    /**
     * Agent's wrap-up notes entered after call completion.
     */
    @Column(name = "wrap_up_notes", columnDefinition = "TEXT")
    private String wrapUpNotes;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
