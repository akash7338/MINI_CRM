package com.minigenesys.userservice.controller;

import com.minigenesys.userservice.dto.CreateAgentRequest;
import com.minigenesys.userservice.dto.CreateSupervisorRequest;
import com.minigenesys.userservice.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/supervisors")
    public ResponseEntity<Void> createSupervisor(@Valid @RequestBody CreateSupervisorRequest request) {
        userService.createSupervisor(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/agents")
    public ResponseEntity<Void> createAgent(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody CreateAgentRequest request) {
        userService.createAgent(request, tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
