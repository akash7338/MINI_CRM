package com.minigenesys.websocket.config;

import com.minigenesys.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthChannelInterceptor implements ChannelInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                try {
                    Claims claims = jwtUtil.getAllClaimsFromToken(token);
                    String tenantId = claims.get("tenantId", String.class);
                    String userId = claims.getSubject();

                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            userId, null, Collections.emptyList());
                    
                    // Store tenantId in session attributes
                    if (accessor.getSessionAttributes() != null) {
                        accessor.getSessionAttributes().put("tenantId", tenantId);
                    }
                    accessor.setUser(auth);
                    log.info("WebSocket connected for user {} in tenant {}", userId, tenantId);
                } catch (Exception e) {
                    log.error("WebSocket auth token validation failed: {}", e.getMessage(), e);
                    throw new IllegalArgumentException("Unauthorized");
                }
            } else {
                log.warn("Missing Authorization header in WebSocket connect");
                throw new IllegalArgumentException("Unauthorized");
            }
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            String tenantId = accessor.getSessionAttributes() != null ? 
                    (String) accessor.getSessionAttributes().get("tenantId") : null;

            if (destination != null && destination.startsWith("/topic/events/")) {
                String requestedTenantId = destination.substring("/topic/events/".length());
                if (tenantId == null || !requestedTenantId.equals(tenantId)) {
                    log.warn("User tried to subscribe to wrong tenant: {} vs {}", requestedTenantId, tenantId);
                    throw new IllegalArgumentException("Forbidden");
                }
            }
        }

        return message;
    }
}
