# User Service

## 1. One-Line Purpose

Manages user account creation (agents + supervisors), authentication (password validation + JWT issuance), and cross-service agent profile provisioning.

---

## 2. When This Service Comes Into Picture

The user-service is involved at the **very beginning** of every user's journey and then never again during active operations:

1. **System Setup** — A supervisor creates agent accounts via `POST /api/v1/users/agents`
2. **Login** — Every time an agent or supervisor opens the dashboard and logs in via `POST /api/v1/auth/login`
3. **JWT Issuance** — The JWT token it generates is used by every subsequent request for the rest of the session (validated by the API Gateway, not by this service)

After login, the user-service is **completely out of the picture**. All real-time operations (heartbeats, call routing, state changes) go through other services.

---

## 3. Responsibilities

1. **Supervisor Account Creation** — Creates users with role `SUPERVISOR` and a BCrypt-hashed password
2. **Agent Account Creation** — Creates users with role `AGENT`, links them to an `agentId`, and makes a synchronous REST call to `agent-state-service` to create the matching agent profile (with skills)
3. **Authentication** — Validates username + password, generates a JWT with embedded claims (`tenantId`, `role`, `agentId`)
4. **JWT Generation** — Uses the shared `JwtUtil` from `shared-common` to sign tokens with an HMAC-SHA256 key
5. **Multi-Tenant Isolation** — All user records are scoped by `tenantId`

---

## 4. APIs Exposed

### `POST /api/v1/auth/login`

Authenticates a user and returns a JWT.

**Request:**
```json
{
  "username": "kumar_akash14",
  "password": "@Kash7338"
}
```

**Response (200 OK):**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1...",
  "userId": "a1b2c3d4-...",
  "tenantId": "tenant1",
  "role": "AGENT",
  "agentId": "AG_001"
}
```

**Error cases:**
- `401 UNAUTHORIZED` — username not found or password doesn't match

### `POST /api/v1/users/supervisors`

Creates a supervisor account. Called during initial system setup.

**Request:**
```json
{
  "tenantId": "tenant1",
  "username": "supervisor1",
  "password": "securePass123"
}
```

**Response:** `201 CREATED` (no body)

**Error cases:**
- `409 CONFLICT` — username already exists

### `POST /api/v1/users/agents`

Creates an agent account AND provisions their agent profile in agent-state-service.

**Request:**
```json
{
  "username": "kumar_akash14",
  "password": "@Kash7338",
  "agentId": "AG_001",
  "name": "Akash Kumar",
  "skills": ["sales", "support"]
}
```

**Headers required:** `X-Tenant-Id: tenant1` (injected by API Gateway from JWT)

**Response:** `201 CREATED` (no body)

**What happens internally:**
1. REST call to `agent-state-service` internal endpoint → creates the agent profile with skills
2. Persists the user record in the local `users` table with `linkedAgentId = AG_001`

**Error cases:**
- `409 CONFLICT` — username already exists
- `500 INTERNAL_SERVER_ERROR` — failed to reach agent-state-service

---

## 5. Kafka Usage

**None — the user-service does not publish or consume any Kafka topics.**

> **Correction note:** Earlier documentation stated that `user-events` is produced by user-service. After reviewing the actual `UserService.java` source code, there is no `KafkaTemplate` or any Kafka dependency in user-service. The `user-events` topic and `KafkaAuditConsumer` references in the audit-service documentation may refer to a planned feature or a different service. The `UserService` class contains only: `createSupervisor()`, `createAgent()`, `createAgentProfileInStateService()`, and `login()` — none of which publish to Kafka.

---

## 6. Redis Usage

**None.** The user-service has no Redis dependency. Authentication is stateless (JWT-based), so there are no sessions to store.

---

## 7. PostgreSQL Usage

### Database: `minigenesys_users`

### Table: `users`

| Column | Type | Description |
|---|---|---|
| `id` | UUID (PK, auto-generated) | Unique user identifier |
| `tenant_id` | VARCHAR | The tenant this user belongs to |
| `username` | VARCHAR (unique) | Login username |
| `password_hash` | VARCHAR | BCrypt-hashed password |
| `role` | ENUM (`SUPERVISOR`, `AGENT`) | Determines access level |
| `linked_agent_id` | VARCHAR (nullable) | Links to the agent profile in agent-state-service. Only set for `AGENT` role users |
| `created_at` | TIMESTAMP | Auto-set on insert |
| `updated_at` | TIMESTAMP | Auto-updated on save |

### Key Queries
- `findByUsername(username)` — Used during login
- `existsByUsername(username)` — Used during registration to check uniqueness

---

## 8. Important State Changes

The user-service doesn't have complex state transitions. A user is simply created and then used for login:

```
                 ┌───────────────┐
                 │  User Created │
                 │ (INSERT into  │
                 │   users table)│
                 └───────┬───────┘
                         │
              ┌──────────▼──────────┐
              │ User Logs In        │
              │ (password validated, │
              │  JWT issued)        │
              └─────────────────────┘
```

The `linkedAgentId` field is the **bridge** between the user-service world and the agent-state-service world. When the JWT is generated, the `agentId` is embedded as a claim so the frontend and gateway always know which agent profile this user maps to.

---

## 9. Interaction With Other Services

| Direction | Service | How | Why |
|---|---|---|---|
| **Calls →** | Agent State Service | `RestTemplate.postForEntity(agentStateServiceUrl, entity, String.class)` via `createAgentProfileInStateService()` | When creating an agent user, provisions the agent profile (with skills) in agent-state-service |
| **Called by ←** | API Gateway | HTTP proxy | Gateway forwards login and user creation requests |
| **Shares →** | `shared-common` (JwtUtil) | Compile-time dependency | Both user-service and api-gateway use the same `JwtUtil` class and HMAC secret |

### Cross-Service Agent Provisioning Flow (Exact Methods)

```
Browser: POST /api/v1/users/agents   (Supervisor's JWT in Authorization header)
→ API Gateway: JwtAuthenticationFilter.filter()
    checks role == SUPERVISOR? → allows /api/v1/users/**
    injects X-Tenant-Id from JWT claims
→ UserController.createAgent(@RequestHeader("X-Tenant-Id") tenantId, @RequestBody request)
→ UserService.createAgent(request, tenantId)

    Step 1: UserService.createAgent()
    → userRepository.existsByUsername(request.getUsername())
        if true → throw 409 CONFLICT

    Step 2: UserService.createAgentProfileInStateService(request, tenantId)
    → builds HttpHeaders:
        headers.set("X-Tenant-Id", tenantId)
        headers.set("X-Internal-Key", internalKey)   // shared secret, bypasses JWT
    → builds body map: { agentId, name, skills }
    → restTemplate.postForEntity(
          "http://localhost:8086/api/v1/agents/internal",
          HttpEntity(body, headers),
          String.class
      )
    → agent-state-service: AgentStateController.createAgent()
          checks X-Internal-Key validity
          AgentStateService.createAgent() → agentRepository.save() [PG: status=OFFLINE]
    → if response not 2xx → throws RuntimeException → propagated as 500

    Step 3 (only if Step 2 succeeded):
    → userRepository.save(User { tenantId, username, passwordHash, role=AGENT, linkedAgentId })
    → returns 201 CREATED

    If Step 2 fails: Step 3 never runs (@Transactional rolls back), user NOT created.
    This is a simple distributed fail-fast pattern (no formal saga).
```

### Login → JWT → Gateway Flow (Exact Methods)

```
Browser: POST /api/v1/auth/login  { username, password }
→ API Gateway: JwtAuthenticationFilter.isSecured("/api/v1/auth/login")
    → OPEN_ENDPOINTS contains "/api/v1/auth/login" → returns false
    → filter skips all JWT checks, calls chain.filter() directly
→ UserController.login(@RequestBody AuthRequest)
→ UserService.login(request)
    → userRepository.findByUsername(request.getUsername())
        if empty → throw 401 UNAUTHORIZED
    → passwordEncoder.matches(request.getPassword(), user.getPasswordHash())
        BCrypt comparison
        if false → throw 401 UNAUTHORIZED
    → jwtUtil.generateToken(
          user.getId().toString(),    // becomes JWT "sub" claim
          user.getTenantId(),          // embedded as "tenantId" claim
          user.getRole().name(),       // embedded as "role" claim
          user.getLinkedAgentId()      // embedded as "agentId" claim
      )
    → returns AuthResponse { accessToken, userId, tenantId, role, agentId }

Browser stores token in localStorage.
Every subsequent request includes: Authorization: Bearer <token>
API Gateway validates via JwtAuthenticationFilter.filter() → never hits user-service again.
```

---

## 10. Edge Cases / Failure Scenarios

| Scenario | What Happens |
|---|---|
| **Duplicate username** | `existsByUsername()` returns true → `409 CONFLICT` |
| **Wrong password on login** | `BCrypt.matches()` returns false → `401 UNAUTHORIZED` |
| **Agent-state-service is down during agent creation** | The REST call throws an exception, the transaction rolls back, and the user is NOT created → `500 INTERNAL_SERVER_ERROR` |
| **JWT secret mismatch between services** | Tokens generated by user-service will fail validation in api-gateway → every authenticated request returns 401. This is why both services share the same secret via `shared-common` config |
| **Token expiry** | JWTs expire after 1 hour (`expirationMs: 3600000`). The frontend must re-login |
| **Creating an agent with an agentId that already exists** | The agent-state-service returns `409 CONFLICT`, which the user-service propagates as `500 INTERNAL_SERVER_ERROR` |

### Security Note

The `X-Internal-Key` header is used to authenticate internal service-to-service calls to the agent-state-service's `/internal` endpoint. This prevents external callers from creating agent profiles directly (the API Gateway blocks `/api/v1/agents/internal` with a 403).

---

## 11. Interview Explanation

> "The user-service handles two things: account management and authentication. When a supervisor creates an agent account, the service does two things atomically — it creates the user record in its own PostgreSQL database with a BCrypt-hashed password, and it makes a synchronous REST call to the agent-state-service to provision the agent's routing profile with their skills. For login, it validates the password and generates a JWT that contains the user's tenant ID, role, and linked agent ID as claims. This JWT is then used by every subsequent request — the API Gateway validates it and extracts the tenant/role/agent info as headers, so no other service ever needs to parse the token again. The user-service is essentially a 'gate-opener' — it's critical at the start of a session but completely dormant during real-time operations."

### JWT Claims Structure (Worth Mentioning)

```json
{
  "sub": "a1b2c3d4-user-uuid",       // userId
  "tenantId": "tenant1",              // multi-tenant scope
  "role": "AGENT",                    // RBAC enforcement
  "agentId": "AG_001",                // links to agent-state-service
  "iat": 1714567200,                  // issued at
  "exp": 1714570800                   // expires in 1 hour
}
```

This structure is important because:
- `tenantId` ensures every downstream query is scoped to the correct tenant
- `agentId` allows the frontend to know which agent profile to fetch
- `role` is used by the API Gateway for RBAC checks
- The token is signed with HMAC-SHA256, so it can't be tampered with
