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
                    String userId   = claims.getSubject();

                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            userId, null, Collections.emptyList());

                    if (accessor.getSessionAttributes() != null) {
                        accessor.getSessionAttributes().put("tenantId", tenantId);
                        accessor.getSessionAttributes().put("userId",   userId);
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
            if (destination == null) return message;

            String sessionTenantId = accessor.getSessionAttributes() != null ?
                    (String) accessor.getSessionAttributes().get("tenantId") : null;
            String sessionUserId = accessor.getSessionAttributes() != null ?
                    (String) accessor.getSessionAttributes().get("userId") : null;

            // /topic/events/{tenantId} — shared tenant broadcast
            if (destination.startsWith("/topic/events/")) {
                String requestedTenantId = destination.substring("/topic/events/".length());
                if (sessionTenantId == null || !requestedTenantId.equals(sessionTenantId)) {
                    log.warn("SUBSCRIBE forbidden: user tried to subscribe to wrong tenant topic: {}", destination);
                    throw new IllegalArgumentException("Forbidden");
                }
            }

            // /topic/{tenantId}/user/{userId} — personal logout notification channel
            // Users may only subscribe to their own user channel.
            if (destination.matches("/topic/[^/]+/user/[^/]+")) {
                String[] parts = destination.split("/");
                // parts = ["", "topic", tenantId, "user", userId]
                if (parts.length == 5) {
                    String destTenantId = parts[2];
                    String destUserId   = parts[4];
                    if (!destTenantId.equals(sessionTenantId) || !destUserId.equals(sessionUserId)) {
                        log.warn("SUBSCRIBE forbidden: {} tried to subscribe to user channel {}", sessionUserId, destination);
                        throw new IllegalArgumentException("Forbidden");
                    }
                }
            }
        }

        return message;
    }
}
