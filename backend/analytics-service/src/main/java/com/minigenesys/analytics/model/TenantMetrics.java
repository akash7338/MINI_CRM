package com.minigenesys.analytics.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Version;
import java.time.Instant;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tenant_metrics")
public class TenantMetrics {
    
    @Id
    private String tenantId;
    
    @Version
    private Long version;
    
    @Builder.Default
    private Long totalCalls = 0L;
    @Builder.Default
    private Long queuedCalls = 0L;
    @Builder.Default
    private Long routedCalls = 0L;
    @Builder.Default
    private Long completedCalls = 0L;
    @Builder.Default
    private Long abandonedCalls = 0L;
    @Builder.Default
    private Long noAgentEvents = 0L;
    
    @Builder.Default
    private Long activeAgents = 0L;
    @Builder.Default
    private Long busyAgents = 0L;
    @Builder.Default
    private Long offlineAgents = 0L;
    
    @Builder.Default
    private Double averageWaitTimeMs = 0.0;
    @Builder.Default
    private Long waitTimeCount = 0L; // Used to calculate rolling average
    
    private Instant updatedAt;
}
