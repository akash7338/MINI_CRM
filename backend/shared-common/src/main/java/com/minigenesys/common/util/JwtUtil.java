package com.minigenesys.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "jwt.secret")
public class JwtUtil {

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expirationMs:3600000}") long expirationMs) {
        this.expirationMs = expirationMs;
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                "JWT secret must be at least 32 bytes (256 bits) for HMAC-SHA256. " +
                "Current length: " + keyBytes.length + " bytes. Update 'jwt.secret' in application.yml.");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public Claims getAllClaimsFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String generateToken(String userId, String tenantId, String role, String agentId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("tenantId", tenantId);
        claims.put("role", role);
        if (agentId != null) {
            claims.put("agentId", agentId);
        }

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userId)
                .setId(UUID.randomUUID().toString())   // jti — unique per issuance
                .setIssuedAt(new Date())
                .setExpiration(new Date((new Date()).getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * Builds the Redis blacklist key using the JWT's unique jti claim.
     * jti is a UUID generated per-token, so two tokens issued within the same
     * second for the same user will always have different blacklist keys.
     */
    public static String blacklistKey(String jti) {
        return "blacklist:" + jti;
    }
}
