# API Gateway

## 1. One-Line Purpose

The single entry point for all external HTTP traffic — validates JWTs, extracts tenant identity, enforces role-based access control, and proxies requests to downstream microservices.

---

## 2. When This Service Comes Into Picture

**Every single HTTP request** from the Angular dashboard passes through the API Gateway before reaching any backend service. It is the first thing that touches a request and the last thing that touches a response.

- User opens the dashboard → login request goes through the gateway
- Agent clicks "Start Shift" → the gateway validates the JWT, then proxies to agent-state-service
- Agent clicks "Simulate Call" → the gateway proxies to call-service
- Dashboard fetches analytics → the gateway enforces role restrictions (agents can't see analytics)

The **only** traffic that bypasses the gateway:
- Internal service-to-service REST calls (e.g., user-service calling agent-state-service directly)
- Twilio webhook callbacks hit the telephony-service directly (but the gateway does proxy them and marks them as open endpoints)

---

## 3. Responsibilities

1. **JWT Validation** — Parses the `Authorization: Bearer <token>` header, validates the signature and expiry using `JwtUtil`
2. **Tenant Extraction** — Extracts `tenantId` from JWT claims and injects it as the `X-Tenant-Id` header for downstream services
3. **Identity Propagation** — Injects `X-User-Id`, `X-User-Role`, and `X-Agent-Id` headers so downstream services know who is calling
4. **Role-Based Access Control (RBAC)** — Blocks agents from accessing `/api/v1/users/*` and `/api/v1/analytics/*`; prevents agents from accessing other agents' profiles
5. **Route Proxying** — Maps URL paths to downstream service URLs using Spring Cloud Gateway route definitions
6. **CORS Configuration** — Allows the Angular dev server (`localhost:4200`) to make cross-origin requests
7. **Open Endpoint Management** — Allows unauthenticated access to login, health checks, WebSocket handshakes, and Twilio webhooks

---

## 4. APIs Exposed

The gateway doesn't have its own REST controllers. It is purely a **reverse proxy** with a security filter. Every route maps to a downstream service (from `application.yml`):

| Route ID | Path Pattern | Downstream Service | Port |
|---|---|---|---|
| `agent-state-internal-block` | `/api/v1/agents/internal` | agent-state-service | 8086 (blocked: returns 403 immediately via `SetStatus=403` filter) |
| `user-service` | `/api/v1/auth/**`, `/api/v1/users/**` | user-service | 8090 |
| `agent-state-service` | `/api/v1/agents/**` | agent-state-service | 8086 |
| `call-service` | `/api/v1/calls/**` | call-service | 8087 |
| `analytics-service` | `/api/v1/analytics/**` | analytics-service | 8089 |
| `websocket-gateway` | `/api/v1/websocket/**`, `/ws/**` | websocket-gateway | 8088 |
| `telephony-service` | `/api/v1/telephony/**` | telephony-service | 8092 |

**Security note on the internal block:**
The route `agent-state-internal-block` uses Spring Cloud Gateway's `SetStatus=403` filter. This means the gateway matches the path and immediately returns 403 **before** the request ever reaches the `JwtAuthenticationFilter`. Route matching happens before global filters run.

### Open Endpoints (no JWT required)

```java
private static final List<String> OPEN_ENDPOINTS = List.of(
    "/api/v1/auth/login",
    "/actuator/health",
    "/ws",
    "/api/v1/telephony/twilio/"
);
```

---

## 5. Kafka Usage

**None.** The API Gateway is purely synchronous. It does not produce or consume any Kafka topics.

---

## 6. Redis Usage

**None.** The gateway is stateless. JWT validation is done in-memory using the shared HMAC secret key.

---

## 7. PostgreSQL Usage

**None.** The gateway has no database. It is fully stateless.

---

## 8. Important State Changes

The gateway is stateless, so it has no internal state transitions. However, it controls the flow of information by:

1. **Adding headers** — Every authenticated request gets `X-Tenant-Id`, `X-User-Id`, `X-User-Role`, and optionally `X-Agent-Id` injected
2. **Blocking requests** — Returns `401 UNAUTHORIZED` (missing/invalid token), `403 FORBIDDEN` (missing tenant ID, wrong role, or accessing another agent's data)

### RBAC Rules Applied

```
IF role == AGENT:
  ✗ Cannot access /api/v1/users/*
  ✗ Cannot access /api/v1/analytics/*
  ✗ Cannot access /api/v1/agents/{otherAgentId} (only their own agent ID)
  ✓ Can access /api/v1/agents/{ownAgentId}
  ✓ Can access /api/v1/calls/*
```

---

## 9. Request Lifecycle (Annotated — Exact Methods)

This is the complete journey of a single authenticated request through the gateway:

```
Browser sends:
  POST /api/v1/agents/AG_001/login
  Authorization: Bearer eyJhbGci...

Step 1: Route Resolution (application.yml — happens FIRST, before any filter)
  Gateway checks predicates in order:
  ✗ /api/v1/agents/internal? No match
  ✓ /api/v1/agents/**? Match!
  → Tags request with destination: http://localhost:8086
  → (If no route matched at all → 404, filter never runs)

Step 2: JwtAuthenticationFilter.filter() [GlobalFilter, Order = -1]
  → isSecured("/api/v1/agents/AG_001/login")?
      OPEN_ENDPOINTS.stream().noneMatch(path::startsWith)  → true (secured)
  → request.getHeaders().containsKey(AUTHORIZATION)? → yes
  → authHeader.startsWith("Bearer ")? → yes
  → token = authHeader.substring(7)
  → jwtUtil.validateToken(token)
      → JwtUtil.validateToken(): parses signature + checks expiry
      → if false → onError(exchange, "Invalid or expired token", 401)
  → claims = jwtUtil.getAllClaimsFromToken(token)
      → extracts: tenantId, role, agentId, userId (from claims.getSubject())
  → tenantId null check → if null → onError(403)
  → RBAC check (only if role == "AGENT"):
      path.startsWith("/api/v1/users/") → false
      path.startsWith("/api/v1/analytics/") → false
      path.startsWith("/api/v1/agents/") && path != "/api/v1/agents/" + agentId → false
  → exchange.getRequest().mutate().headers(h -> {
        h.set("X-Tenant-Id", tenantId);   // OVERWRITES any browser-sent value
        h.set("X-User-Id", userId);
        h.set("X-User-Role", role);
        h.set("X-Agent-Id", agentId);      // only if agentId != null
    })
  → chain.filter(exchange)  // passes to next filter → routing

Step 3: NettyRoutingFilter (built-in, runs last — NOT in application.yml)
  → reads the destination URL tagged in Step 1
  → opens TCP connection to http://localhost:8086
  → forwards the mutated request (with X-Tenant-Id etc.) over the network
  → streams the response back to the browser

Step 4: DedupeResponseHeader (default-filters in application.yml)
  - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST
  → On the RESPONSE path (exit side):
      if response has duplicate CORS headers (from both gateway and downstream),
      keeps only the FIRST occurrence
  → Prevents browser CORS errors caused by double-injection
```

---

## 10. Interaction With Other Services

| Direction | Service | How | Why |
|---|---|---|---|
| Proxies to | All 7 backend services | HTTP reverse proxy | Routes every external request |
| Uses | `shared-common` (JwtUtil) | Compile-time dependency | JWT parsing and validation |

**The gateway never calls any service's API directly.** It only forwards the original client request with added headers.

---

## 10. Edge Cases / Failure Scenarios

| Scenario | What Happens |
|---|---|
| **JWT expired** | Gateway returns `401 UNAUTHORIZED`, browser redirects to login |
| **JWT has no tenantId claim** | Gateway returns `403 FORBIDDEN: Tenant ID missing from token` |
| **Agent tries to access analytics** | Gateway returns `403 FORBIDDEN: Access denied to analytics API` |
| **Agent tries to access another agent's profile** | Gateway compares the URL's agentId with the JWT's agentId; returns 403 if they don't match |
| **Downstream service is down** | Gateway returns `503 SERVICE_UNAVAILABLE` (Spring Cloud Gateway default behavior) |
| **Missing Authorization header on secured endpoint** | Gateway returns `401 UNAUTHORIZED: Missing Authorization header` |
| **Malformed token (not JWT)** | `jwtUtil.validateToken()` catches the exception and returns `401` |

---

## 11. Interview Explanation

> "The API Gateway is a Spring Cloud Gateway application that acts as the single entry point for all external HTTP traffic. It implements a custom `GlobalFilter` called `JwtAuthenticationFilter` that runs before every request is routed. The filter validates the JWT signature using a shared HMAC-SHA256 secret, extracts the `tenantId`, `userId`, `role`, and `agentId` claims, and injects them as downstream headers (`X-Tenant-Id`, etc.) so that backend services never need to parse the JWT themselves. It also enforces simple RBAC — for example, agents can only access their own profile endpoint and are blocked from the analytics and user management APIs. The gateway is fully stateless — no Redis, no database, no Kafka — which means it can be horizontally scaled trivially."

### Key Implementation Detail Worth Mentioning

The gateway uses `@EnableWebFluxSecurity` (reactive Spring Security) but intentionally sets `anyExchange().permitAll()`. This is because the actual authentication is handled by the custom `JwtAuthenticationFilter` (a `GlobalFilter`), not by Spring Security's built-in filter chain. Spring Security is only used to disable CSRF (since we use JWT tokens, not cookies).
