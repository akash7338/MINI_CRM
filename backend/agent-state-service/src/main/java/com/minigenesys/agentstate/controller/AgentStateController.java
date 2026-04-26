package com.minigenesys.agentstate.controller;

import com.minigenesys.agentstate.dto.AgentStateResponse;
import com.minigenesys.agentstate.model.AgentStatus;
import com.minigenesys.agentstate.service.AgentStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentStateController {

    private final AgentStateService agentStateService;

    @PostMapping("/{agentId}/login")
    public ResponseEntity<AgentStateResponse> login(
            @PathVariable String agentId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        return ResponseEntity.ok(agentStateService.changeState(tenantId, agentId, AgentStatus.AVAILABLE));
    }

    @PostMapping("/{agentId}/logout")
    public ResponseEntity<AgentStateResponse> logout(
            @PathVariable String agentId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        return ResponseEntity.ok(agentStateService.changeState(tenantId, agentId, AgentStatus.OFFLINE));
    }

    @PostMapping("/{agentId}/available")
    public ResponseEntity<AgentStateResponse> available(
            @PathVariable String agentId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        return ResponseEntity.ok(agentStateService.changeState(tenantId, agentId, AgentStatus.AVAILABLE));
    }

    @PostMapping("/{agentId}/busy")
    public ResponseEntity<AgentStateResponse> busy(
            @PathVariable String agentId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        return ResponseEntity.ok(agentStateService.changeState(tenantId, agentId, AgentStatus.BUSY));
    }

    @PostMapping("/{agentId}/heartbeat")
    public ResponseEntity<Void> heartbeat(
            @PathVariable String agentId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        agentStateService.handleHeartbeat(tenantId, agentId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{agentId}/state")
    public ResponseEntity<AgentStateResponse> getState(
            @PathVariable String agentId,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        return ResponseEntity.ok(agentStateService.getState(tenantId, agentId));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
