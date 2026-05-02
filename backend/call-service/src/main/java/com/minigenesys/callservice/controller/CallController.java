package com.minigenesys.callservice.controller;

import com.minigenesys.callservice.dto.CallResponse;
import com.minigenesys.callservice.dto.CreateCallRequest;
import com.minigenesys.callservice.service.CallService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/calls")
@RequiredArgsConstructor
public class CallController {

    private final CallService callService;

    @PostMapping
    public ResponseEntity<CallResponse> createCall(
            @RequestHeader(value = "X-Tenant-Id", required = true) String tenantId,
            @Valid @RequestBody CreateCallRequest request) {
        CallResponse response = callService.createCall(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{callId}")
    public ResponseEntity<CallResponse> getCall(
            @PathVariable String callId,
            @RequestHeader(value = "X-Tenant-Id", required = true) String tenantId) {
        return ResponseEntity.ok(callService.getCall(callId, tenantId));
    }

    @PostMapping("/{callId}/start")
    public ResponseEntity<CallResponse> startCall(
            @PathVariable String callId,
            @RequestHeader(value = "X-Tenant-Id", required = true) String tenantId) {
        return ResponseEntity.ok(callService.startCall(callId, tenantId));
    }

    @PostMapping("/{callId}/complete")
    public ResponseEntity<CallResponse> completeCall(
            @PathVariable String callId,
            @RequestHeader(value = "X-Tenant-Id", required = true) String tenantId) {
        return ResponseEntity.ok(callService.completeCall(callId, tenantId));
    }

    @PostMapping("/{callId}/reject")
    public ResponseEntity<CallResponse> rejectCall(
            @PathVariable String callId,
            @RequestHeader(value = "X-Tenant-Id", required = true) String tenantId) {
        return ResponseEntity.ok(callService.rejectCall(callId, tenantId));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
