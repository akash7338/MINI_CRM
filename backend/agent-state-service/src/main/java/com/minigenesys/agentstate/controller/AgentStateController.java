package com.minigenesys.agentstate.controller;

import com.minigenesys.agentstate.dto.AgentStateResponse;
import com.minigenesys.agentstate.model.AgentStatus;
import com.minigenesys.agentstate.service.AgentStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.minigenesys.agentstate.dto.CreateAgentRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentStateController {

    @Value("${auth.internal-key}")
    private String internalKey;

    private final AgentStateService agentStateService;

    @PostMapping("/internal")
    public ResponseEntity<AgentStateResponse> createAgent(
            @RequestHeader(value = "X-Internal-Key", required = false) String providedKey,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody CreateAgentRequest request) {
        
        if (providedKey == null || !java.security.MessageDigest.isEqual(
                providedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                internalKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        
        return ResponseEntity.ok(agentStateService.createAgent(tenantId, request));
    }

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

    @GetMapping("/counts")
    public ResponseEntity<Map<String, Long>> getCounts(
            @RequestHeader("X-Tenant-Id") String tenantId) {
        return ResponseEntity.ok(agentStateService.getCounts(tenantId));
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

}
