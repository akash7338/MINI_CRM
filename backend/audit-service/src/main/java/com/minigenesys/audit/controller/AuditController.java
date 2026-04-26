package com.minigenesys.audit.controller;

import com.minigenesys.audit.model.AuditEvent;
import com.minigenesys.audit.repository.AuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditRepository auditRepository;

    @GetMapping("/events")
    public ResponseEntity<List<AuditEvent>> getEvents(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "100") int limit) {

        List<AuditEvent> events = auditRepository.findByFilters(
                tenantId, entityType, entityId, eventType, PageRequest.of(0, limit)
        );

        return ResponseEntity.ok(events);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
