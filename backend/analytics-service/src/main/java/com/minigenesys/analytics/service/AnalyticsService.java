package com.minigenesys.analytics.service;

import com.minigenesys.analytics.model.TenantMetrics;
import com.minigenesys.analytics.repository.TenantMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final TenantMetricsRepository repository;

    public TenantMetrics getMetrics(String tenantId) {
        return repository.findById(tenantId)
                .orElse(TenantMetrics.builder().tenantId(tenantId).updatedAt(Instant.now()).build());
    }

    @Transactional
    public void incrementTotalCalls(String tenantId) {
        updateMetric(tenantId, m -> m.setTotalCalls(m.getTotalCalls() + 1));
    }

    @Transactional
    public void incrementQueuedCalls(String tenantId) {
        updateMetric(tenantId, m -> m.setQueuedCalls(m.getQueuedCalls() + 1));
    }

    @Transactional
    public void incrementRoutedCalls(String tenantId) {
        updateMetric(tenantId, m -> m.setRoutedCalls(m.getRoutedCalls() + 1));
    }

    @Transactional
    public void incrementCompletedCalls(String tenantId) {
        updateMetric(tenantId, m -> m.setCompletedCalls(m.getCompletedCalls() + 1));
    }

    @Transactional
    public void incrementAbandonedCalls(String tenantId) {
        updateMetric(tenantId, m -> m.setAbandonedCalls(m.getAbandonedCalls() + 1));
    }

    @Transactional
    public void incrementNoAgentEvents(String tenantId) {
        updateMetric(tenantId, m -> m.setNoAgentEvents(m.getNoAgentEvents() + 1));
    }

    @Transactional
    public void updateAgentCounts(String tenantId, String statusChange) {
        updateMetric(tenantId, m -> {
            // This is a simplified version since we don't track individual agents here
            // In a real system, we'd use a delta or a more complex state
            switch (statusChange) {
                case "AVAILABLE" -> {
                    m.setActiveAgents(Math.max(0, m.getActiveAgents() + 1));
                }
                case "BUSY" -> {
                    m.setBusyAgents(Math.max(0, m.getBusyAgents() + 1));
                    m.setActiveAgents(Math.max(0, m.getActiveAgents() - 1));
                }
                case "OFFLINE" -> {
                    // Logic would depend on previous status, but let's assume decrease of active/busy
                    m.setOfflineAgents(m.getOfflineAgents() + 1);
                }
            }
        });
    }
    
    @Transactional
    public void setAgentCounts(String tenantId, long active, long busy, long offline) {
        updateMetric(tenantId, m -> {
            m.setActiveAgents(active);
            m.setBusyAgents(busy);
            m.setOfflineAgents(offline);
        });
    }

    @Transactional
    public void updateWaitTime(String tenantId, long waitTimeMs) {
        updateMetric(tenantId, m -> {
            double totalWaitTime = m.getAverageWaitTimeMs() * m.getWaitTimeCount();
            m.setWaitTimeCount(m.getWaitTimeCount() + 1);
            m.setAverageWaitTimeMs((totalWaitTime + waitTimeMs) / m.getWaitTimeCount());
        });
    }

    private void updateMetric(String tenantId, java.util.function.Consumer<TenantMetrics> updater) {
        TenantMetrics metrics = repository.findById(tenantId)
                .orElse(TenantMetrics.builder()
                        .tenantId(tenantId)
                        .totalCalls(0L)
                        .queuedCalls(0L)
                        .routedCalls(0L)
                        .completedCalls(0L)
                        .abandonedCalls(0L)
                        .noAgentEvents(0L)
                        .activeAgents(0L)
                        .busyAgents(0L)
                        .offlineAgents(0L)
                        .averageWaitTimeMs(0.0)
                        .waitTimeCount(0L)
                        .build());
        
        updater.accept(metrics);
        metrics.setUpdatedAt(Instant.now());
        repository.save(metrics);
    }
}
