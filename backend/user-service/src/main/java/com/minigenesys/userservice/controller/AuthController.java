package com.minigenesys.userservice.controller;

import com.minigenesys.userservice.dto.AuthRequest;
import com.minigenesys.userservice.dto.AuthResponse;
import com.minigenesys.userservice.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }

    /**
     * Session activation — called by the frontend every time the app loads in a new tab.
     *
     * Implements the spec's "latest wins" policy:
     *   - If another session is active (loginAt > 0), the old tab is kicked immediately
     *     via a WebSocket LogoutNotification. The old JWT is also added to the Redis
     *     token blacklist, so the old tab's next HTTP request returns 403 / TOKEN_EXPIRED
     *     even if it missed the WebSocket push.
     *   - A fresh JWT is issued for the new tab and the session record is stamped.
     *
     * The gateway bypasses the blacklist check for this endpoint so a legitimate new tab
     * can call activate with the localStorage token even if that token was previously
     * blacklisted on another node. The frontend prevents abuse via the localStorage
     * "reason" flag: after a forced kick the UI shows the login screen and does NOT call
     * activate again automatically.
     */
    @PostMapping("/activate")
    public ResponseEntity<AuthResponse> activate(
            @RequestHeader("X-User-Id")  String userId,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Role") String role,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        return ResponseEntity.ok(userService.activateSession(userId, tenantId, role, agentId));
    }
}
