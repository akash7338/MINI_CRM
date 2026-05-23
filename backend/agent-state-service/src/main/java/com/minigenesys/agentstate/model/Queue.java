package com.minigenesys.agentstate.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.util.Set;

@Entity
@Table(name = "queues")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Queue {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "queue_agents", joinColumns = @JoinColumn(name = "queue_id"))
    @Column(name = "agent_id", nullable = false)
    private Set<String> agentIds;

    @Column(nullable = false)
    private boolean disableSkills;

    @Column(nullable = false)
    private String assignmentType; // "FIFO" | "LIFO"

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
