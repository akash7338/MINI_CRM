package com.minigenesys.routing.controller;

import com.minigenesys.routing.dto.AssignmentResult;
import com.minigenesys.routing.dto.CallRequest;
import com.minigenesys.routing.model.Assignment;
import com.minigenesys.routing.service.RoutingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/routing")
@RequiredArgsConstructor
public class RoutingController {

    private final RoutingService routingService;

    @PostMapping("/assign")
    public ResponseEntity<AssignmentResult> assign(@Valid @RequestBody CallRequest request) {
        return ResponseEntity.ok(routingService.processRouting(request));
    }

    @GetMapping("/assignments/{callId}")
    public ResponseEntity<Assignment> getAssignment(@PathVariable String callId) {
        return routingService.getAssignment(callId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
