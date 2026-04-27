package com.minigenesys.apigateway.filter;

import com.minigenesys.apigateway.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;

    private static final List<String> OPEN_ENDPOINTS = List.of(
            "/api/v1/auth/login",
            "/actuator/health",
            "/ws",
            "/api/v1/telephony/twilio/"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
 
        // Check if path is open
        if (isSecured(path)) {
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                return onError(exchange, "Missing Authorization header", HttpStatus.UNAUTHORIZED);
            }

            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return onError(exchange, "Invalid Authorization header format", HttpStatus.UNAUTHORIZED);
            }

            String token = authHeader.substring(7);

            if (!jwtUtil.validateToken(token)) {
                return onError(exchange, "Invalid or expired token", HttpStatus.UNAUTHORIZED);
            }

            try {
                Claims claims = jwtUtil.getAllClaimsFromToken(token);
                String tenantId = claims.get("tenantId", String.class);
                String role = claims.get("role", String.class);
                String agentId = claims.get("agentId", String.class);
                String userId = claims.getSubject();

                if (tenantId == null || tenantId.isEmpty()) {
                    return onError(exchange, "Tenant ID missing from token", HttpStatus.FORBIDDEN);
                }

                // Simple RBAC
                if ("AGENT".equals(role)) {
                    if (path.startsWith("/api/v1/agents/") && agentId != null && !path.startsWith("/api/v1/agents/" + agentId)) {
                        return onError(exchange, "Access denied to other agent profiles", HttpStatus.FORBIDDEN);
                    }
                    if (path.startsWith("/api/v1/users/")) {
                        return onError(exchange, "Access denied to users API", HttpStatus.FORBIDDEN);
                    }
                    if (path.startsWith("/api/v1/analytics/")) {
                        return onError(exchange, "Access denied to analytics API", HttpStatus.FORBIDDEN);
                    }
                }

                // Mutate the request to add headers
                ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate()
                        .header("X-Tenant-Id", tenantId)
                        .header("X-User-Id", userId != null ? userId : "")
                        .header("X-User-Role", role != null ? role : "");
                        
                if (agentId != null && !agentId.isBlank()) {
                    requestBuilder.header("X-Agent-Id", agentId);
                }

                ServerHttpRequest mutatedRequest = requestBuilder.build();
                exchange = exchange.mutate().request(mutatedRequest).build();

            } catch (Exception e) {
                log.error("Token parsing error: ", e);
                return onError(exchange, "Failed to parse token claims", HttpStatus.UNAUTHORIZED);
            }
        }

        return chain.filter(exchange);
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        log.error("Authentication failed: {}", err);
        exchange.getResponse().setStatusCode(httpStatus);
        return exchange.getResponse().setComplete();
    }

    private boolean isSecured(String path) {
        return OPEN_ENDPOINTS.stream().noneMatch(path::contains);
    }

    @Override
    public int getOrder() {
        return -1; // Ensure this runs before routing
    }
}
