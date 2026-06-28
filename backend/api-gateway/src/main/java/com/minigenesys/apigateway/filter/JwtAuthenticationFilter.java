package com.minigenesys.apigateway.filter;

import com.minigenesys.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;
    private final ReactiveStringRedisTemplate redisTemplate;

    /** No JWT validation at all — public access. */
    private static final List<String> OPEN_ENDPOINTS = List.of(
            "/api/v1/auth/login",
            "/actuator/health",
            "/api/v1/diagnostics/",
            "/ws",
            "/api/v1/telephony/twilio/");

    /**
     * JWT signature is validated and downstream headers are set, but the Redis
     * blacklist check is skipped.
     *
     * Used for /auth/activate so that a new tab can claim the session using the
     * existing localStorage token — even if that token was previously blacklisted
     * on another cluster node. The frontend prevents abuse via the localStorage
     * "reason" flag: after a forced kick the UI shows the login page and does NOT
     * call /activate automatically.
     */
    private static final List<String> BLACKLIST_BYPASS_ENDPOINTS = List.of(
            "/api/v1/auth/activate");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. Completely open endpoints — pass straight through.
        if (isOpen(path)) {
            return chain.filter(exchange);
        }

        // 2. Validate JWT signature / presence.
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return onError(exchange, "UNAUTHORIZED", "Missing or invalid Authorization header", HttpStatus.UNAUTHORIZED);
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return onError(exchange, "UNAUTHORIZED", "Invalid or expired token", HttpStatus.UNAUTHORIZED);
        }

        Claims claims;
        try {
            claims = jwtUtil.getAllClaimsFromToken(token);
        } catch (Exception e) {
            log.error("Token parsing error: ", e);
            return onError(exchange, "UNAUTHORIZED", "Failed to parse token claims", HttpStatus.UNAUTHORIZED);
        }

        String tenantId = claims.get("tenantId", String.class);
        String role     = claims.get("role",     String.class);
        String agentId  = claims.get("agentId",  String.class);
        String userId   = claims.getSubject();

        if (tenantId == null || tenantId.isEmpty()) {
            return onError(exchange, "FORBIDDEN", "Tenant ID missing from token", HttpStatus.FORBIDDEN);
        }

        // 3. Simple RBAC.
        if ("AGENT".equals(role)) {
            if (path.startsWith("/api/v1/agents/") && agentId != null
                    && !path.startsWith("/api/v1/agents/" + agentId)) {
                return onError(exchange, "FORBIDDEN", "Access denied to other agent profiles", HttpStatus.FORBIDDEN);
            }
            if (path.startsWith("/api/v1/users/")) {
                return onError(exchange, "FORBIDDEN", "Access denied to users API", HttpStatus.FORBIDDEN);
            }
            if (path.startsWith("/api/v1/analytics/")) {
                return onError(exchange, "FORBIDDEN", "Access denied to analytics API", HttpStatus.FORBIDDEN);
            }
        }

        // 4. Inject downstream headers.
        final String finalUserId  = userId  != null ? userId  : "";
        final String finalRole    = role    != null ? role    : "";
        final String finalAgentId = agentId != null ? agentId : "";
        ServerWebExchange mutated = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(h -> {
                            h.set("X-Tenant-Id", tenantId);
                            h.set("X-User-Id",   finalUserId);
                            h.set("X-User-Role",  finalRole);
                            if (!finalAgentId.isBlank()) h.set("X-Agent-Id", finalAgentId);
                        })
                        .build())
                .build();

        // 5. Bypass blacklist check for session-activation endpoint.
        if (isBlacklistBypass(path)) {
            return chain.filter(mutated);
        }

        // 6. Token blacklist check (per-request).
        // Key format: "blacklist:{jti}" where jti is the UUID embedded in the JWT.
        // Blacklisted tokens belong to sessions that were kicked by a newer login.
        // The old tab's next HTTP request hits this check and receives 403 TOKEN_EXPIRED.
        String jti = claims.getId();
        long expMillis = claims.getExpiration().getTime();
        // Fall back to userId_exp for old tokens that pre-date the jti field.
        String blacklistKey = (jti != null && !jti.isBlank())
                ? JwtUtil.blacklistKey(jti)
                : ("blacklist:" + finalUserId + "_" + expMillis);

        return redisTemplate.hasKey(blacklistKey)
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        log.info("TOKEN_EXPIRED (blacklisted) for user {} key={}", finalUserId, blacklistKey);
                        return onTokenExpired(exchange);
                    }
                    return chain.filter(mutated);
                })
                .switchIfEmpty(chain.filter(mutated));
    }

    /**
     * Returns HTTP 403 with the spec-mandated body:
     *   {"errorCode": 403020, "message": "TOKEN_EXPIRED"}
     *
     * The frontend's HTTP interceptor catches this exact shape and triggers
     * the force-logout flow (clear in-memory token, show login with reason message).
     */
    private Mono<Void> onTokenExpired(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"errorCode\":403020,\"message\":\"TOKEN_EXPIRED\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> onError(ServerWebExchange exchange, String code, String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"code\":\"%s\",\"message\":\"%s\"}", code, message);
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private boolean isOpen(String path) {
        return OPEN_ENDPOINTS.stream().anyMatch(path::startsWith);
    }

    private boolean isBlacklistBypass(String path) {
        return BLACKLIST_BYPASS_ENDPOINTS.stream().anyMatch(path::startsWith);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
