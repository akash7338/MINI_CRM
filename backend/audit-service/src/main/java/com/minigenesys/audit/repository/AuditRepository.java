package com.minigenesys.audit.repository;

import com.minigenesys.audit.model.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditRepository extends JpaRepository<AuditEvent, UUID> {

    @Query("SELECT a FROM AuditEvent a WHERE " +
           "(:tenantId IS NULL OR a.tenantId = :tenantId) AND " +
           "(:entityType IS NULL OR a.entityType = :entityType) AND " +
           "(:entityId IS NULL OR a.entityId = :entityId) AND " +
           "(:eventType IS NULL OR a.eventType = :eventType) " +
           "ORDER BY a.createdAt DESC")
    List<AuditEvent> findByFilters(
            @Param("tenantId") String tenantId,
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("eventType") String eventType,
            Pageable pageable);
}
