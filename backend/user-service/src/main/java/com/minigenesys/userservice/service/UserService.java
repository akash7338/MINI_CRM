package com.minigenesys.userservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigenesys.userservice.dto.*;
import com.minigenesys.userservice.model.Role;
import com.minigenesys.userservice.model.Tenant;
import com.minigenesys.userservice.model.User;
import com.minigenesys.userservice.repository.TenantRepository;
import com.minigenesys.userservice.repository.UserRepository;
import com.minigenesys.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // Injected from RestTemplateConfig with connect/read timeouts configured
    private final RestTemplate restTemplate;

    @Value("${services.agent-state.url:http://localhost:8086/api/v1/agents/internal}")
    private String agentStateServiceUrl;

    @Value("${auth.internal-key}")
    private String internalKey;

    @Value("${jwt.expirationMs:3600000}")
    private long jwtExpirationMs;

    private static final String SESSION_KEY_PREFIX = "user:";
    private static final String SESSION_KEY_SUFFIX = ":session";
    private static final String FIELD_TOKEN    = "token";
    private static final String FIELD_LOGIN_AT = "loginAt";
    private static final String KAFKA_AUTH_TOPIC = "auth-events";

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
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not create agent profile in state service");
        }
    }

    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CUSTOM: User Not Found"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CUSTOM: Password Mismatch");
        }

        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> {
                    log.error("Login failed: tenant '{}' not found in tenants table.", user.getTenantId());
                    return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Tenant configuration missing. Contact your administrator.");
                });

        log.info("User '{}' (tenant: {}, provider: {}) authenticated successfully.",
                user.getUsername(), tenant.getId(), tenant.getTelephonyProvider());

        String newToken = jwtUtil.generateToken(user.getId().toString(), user.getTenantId(),
                user.getRole().name(), user.getLinkedAgentId());

        kickExistingSessionIfPresent(user.getId().toString(), user.getTenantId(), null);
        stampSession(user.getId().toString(), newToken);

        return AuthResponse.builder()
                .accessToken(newToken)
                .userId(user.getId())
                .tenantId(user.getTenantId())
                .role(user.getRole())
                .agentId(user.getLinkedAgentId())
                .telephonyProvider(tenant.getTelephonyProvider())
                .build();
    }

    /**
     * Called when a new tab opens with an existing JWT (tab duplication / page refresh).
     * Per the spec's "latest wins" policy:
     *   1. If another session is currently active (loginAt > 0), send a logout notification
     *      to that session via WebSocket and blacklist the old token.
     *   2. Issue a fresh JWT and stamp the new session.
     *
     * Gateway bypasses the token blacklist check for this endpoint so a kicked tab can
     * still call activate with its old (blacklisted) token as a bootstrap credential.
     * The frontend prevents this via the localStorage "reason" flag — after a forced kick
     * the frontend shows the login screen and does not call activate automatically.
     */
    public AuthResponse activateSession(String userId, String tenantId, String role, String agentId) {
        String newToken = jwtUtil.generateToken(userId, tenantId, role,
                (agentId != null && !agentId.isBlank()) ? agentId : null);

        kickExistingSessionIfPresent(userId, tenantId, null);
        stampSession(userId, newToken);

        log.info("Session activated for user {}.", userId);
        return AuthResponse.builder()
                .accessToken(newToken)
                .build();
    }

    // -----------------------------------------------------------------------
    // Session store helpers (Redis hash: user:{userId}:session)
    // -----------------------------------------------------------------------

    /**
     * If a session with loginAt > 0 already exists for this user, push a WebSocket
     * logout notification and blacklist the old token so the old tab is kicked immediately
     * (WebSocket path) or on its next HTTP request (HTTP 403 fallback path).
     */
    private void kickExistingSessionIfPresent(String userId, String tenantId, String skipToken) {
        HashOperations<String, String, String> hash = redisTemplate.opsForHash();
        String sessionKey = SESSION_KEY_PREFIX + userId + SESSION_KEY_SUFFIX;

        String loginAtStr = hash.get(sessionKey, FIELD_LOGIN_AT);
        long loginAt = loginAtStr != null ? Long.parseLong(loginAtStr) : 0L;

        if (loginAt > 0) {
            String oldToken = hash.get(sessionKey, FIELD_TOKEN);
            if (oldToken != null && !oldToken.equals(skipToken)) {
                String kickedJti = blacklistToken(oldToken, userId);
                sendLogoutNotification(tenantId, userId, "LOGOUT_AGENT", kickedJti);
            }
        }
    }

    /** Write the new session (token + loginAt = now) to Redis with a generous TTL. */
    private void stampSession(String userId, String token) {
        HashOperations<String, String, String> hash = redisTemplate.opsForHash();
        String sessionKey = SESSION_KEY_PREFIX + userId + SESSION_KEY_SUFFIX;

        Map<String, String> sessionData = new HashMap<>();
        sessionData.put(FIELD_TOKEN,    token);
        sessionData.put(FIELD_LOGIN_AT, String.valueOf(System.currentTimeMillis()));
        hash.putAll(sessionKey, sessionData);

        // TTL slightly longer than the JWT so the entry auto-evicts after the JWT expires.
        Duration ttl = Duration.ofMillis(jwtExpirationMs).plusMinutes(5);
        redisTemplate.expire(sessionKey, ttl);
    }

    /**
     * Add a token to the Redis blacklist. Key = "blacklist:{userId}_{expMillis}".
     * TTL mirrors the token's own remaining lifetime so entries self-evict.
     */
    /**
     * Blacklists the given token in Redis and returns its jti (or null on failure).
     * The jti is included in the LogoutNotification so the new winning tab can
     * ignore the notification (its own token has a different jti).
     */
    private String blacklistToken(String token, String userId) {
        try {
            Claims claims = jwtUtil.getAllClaimsFromToken(token);
            String jti = claims.getId();
            long expMillis = claims.getExpiration().getTime();
            String key = (jti != null && !jti.isBlank())
                    ? JwtUtil.blacklistKey(jti)
                    : ("blacklist:" + userId + "_" + expMillis);
            long ttlSeconds = Math.max(1, (expMillis - System.currentTimeMillis()) / 1000);
            redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ttlSeconds));
            log.info("Blacklisted token for user {} (key={})", userId, key);
            return jti;
        } catch (Exception e) {
            log.warn("Could not blacklist token for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Publish a LogoutNotification event to Kafka. The websocket-gateway consumes
     * this and pushes it to the user's personal STOMP topic:
     *   /topic/{tenantId}/user/{userId}
     */
    /**
     * @param kickedJti jti of the token being kicked — the new winning tab compares
     *                  this against its own token's jti and ignores the notification
     *                  if they don't match (i.e. it is the new winner, not the old tab).
     */
    private void sendLogoutNotification(String tenantId, String userId, String reason, String kickedJti) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("type", "LogoutNotification");
            event.put("tenantId", tenantId);
            event.put("userId", userId);
            event.put("reason", reason);
            event.put("kickedJti", kickedJti);   // Which token was kicked
            event.put("timestamp", System.currentTimeMillis());
            kafkaTemplate.send(KAFKA_AUTH_TOPIC, objectMapper.writeValueAsString(event));
            log.info("LogoutNotification sent for user {} in tenant {} (kickedJti={})", userId, tenantId, kickedJti);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize LogoutNotification: {}", e.getMessage());
        }
    }
}
