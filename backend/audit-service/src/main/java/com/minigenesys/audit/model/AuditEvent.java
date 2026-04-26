package com.minigenesys.audit.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = true)
    private String tenantId;

    @Column(nullable = true)
    private String actorUserId;

    @Column(nullable = true)
    private String actorRole;

    @Column(nullable = true)
    private String entityType;

    @Column(nullable = true)
    private String entityId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String sourceService;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payloadJson;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
