package com.minigenesys.routing.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Assignment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String callId;

    @Column(nullable = false)
    private String agentId;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private Instant assignedAt;
}
