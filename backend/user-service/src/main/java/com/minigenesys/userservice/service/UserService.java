package com.minigenesys.userservice.service;

import com.minigenesys.userservice.dto.*;
import com.minigenesys.common.dto.*;
import com.minigenesys.userservice.model.Role;
import com.minigenesys.userservice.model.User;
import com.minigenesys.userservice.repository.UserRepository;
import com.minigenesys.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    
    // Injected from RestTemplateConfig with connect/read timeouts configured
    private final RestTemplate restTemplate;
    
    @Value("${services.agent-state.url:http://localhost:8086/api/v1/agents/internal}")
    private String agentStateServiceUrl;

    @Value("${auth.internal-key}")
    private String internalKey;

    @Transactional
    public void createSupervisor(CreateSupervisorRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }

        User user = User.builder()
                .tenantId(request.getTenantId())
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.SUPERVISOR)
                .build();

        userRepository.save(user);
        log.info("Created supervisor user: {}", request.getUsername());
    }

    @Transactional
    public void createAgent(CreateAgentRequest request, String tenantId) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }

        // 1. Create the matching agent profile in agent-state-service
        createAgentProfileInStateService(request, tenantId);

        // 2. Create the user
        User user = User.builder()
                .tenantId(tenantId)
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.AGENT)
                .linkedAgentId(request.getAgentId())
                .build();

        userRepository.save(user);
        log.info("Created agent user: {} for agentId: {}", request.getUsername(), request.getAgentId());
    }

    private void createAgentProfileInStateService(CreateAgentRequest request, String tenantId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Tenant-Id", tenantId);
            headers.set("X-Internal-Key", internalKey);
            
            Map<String, Object> body = new HashMap<>();
            body.put("agentId", request.getAgentId());
            body.put("name", request.getName());
            body.put("skills", request.getSkills());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(agentStateServiceUrl, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Failed to create agent profile. Status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Error communicating with agent-state-service: ", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create agent profile in state service");
        }
    }

    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        String token = jwtUtil.generateToken(user.getId().toString(), user.getTenantId(), user.getRole().name(), user.getLinkedAgentId());

        return AuthResponse.builder()
                .accessToken(token)
                .userId(user.getId())
                .tenantId(user.getTenantId())
                .role(user.getRole())
                .agentId(user.getLinkedAgentId())
                .build();
    }
}
