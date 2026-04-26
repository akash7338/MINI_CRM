package com.minigenesys.agentstate.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Set;

@Entity
@Table(name = "agents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Agent {

    @Id
    @Column(nullable = false, updatable = false)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_skills", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "skill")
    private Set<String> skills;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentStatus status;

    private Long lastAssignedTime;
    private Long lastHeartbeatAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
