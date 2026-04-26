package com.minigenesys.analytics.repository;

import com.minigenesys.analytics.model.TenantMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantMetricsRepository extends JpaRepository<TenantMetrics, String> {
}
