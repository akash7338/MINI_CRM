# Agent State Service

## 1. One-Line Purpose

The real-time source of truth for agent availability — tracks whether each agent is AVAILABLE, BUSY, or OFFLINE using Redis + PostgreSQL, detects browser crashes via heartbeats, and publishes every state change to Kafka.

---

## 2. When This Service Comes Into Picture

This service is involved in **almost every operation** in the system:

- **Agent logs in** → changes state from OFFLINE → AVAILABLE
- **Call is assigned** → routing-events arrive, state changes AVAILABLE → BUSY
- **Call is completed** → call-lifecycle-events arrive, state changes BUSY → AVAILABLE
- **Agent logs out** → changes state from AVAILABLE → OFFLINE
- **Every 15 seconds** → receives heartbeat pings from the browser
- **Every 10 seconds** → runs disconnect detection scan
- **Agent account created** → user-service calls the internal endpoint to provision the agent profile

---

## 3. Responsibilities

1. **Agent Profile Management** — CRUD operations for agent records (id, name, skills, tenant)
2. **State Machine Enforcement** — Validates all state transitions (e.g., blocks OFFLINE → BUSY)
3. **Dual Write (Redis + PostgreSQL)** — Every state change is written to both Redis (for real-time routing) and PostgreSQL (for durability)
4. **Heartbeat Processing** — Receives pings every 15s and updates the heartbeat timestamp in Redis (TTL 30s) and PostgreSQL
5. **Disconnect Detection** — Scheduled job (`@Scheduled`) runs every 10s, scans for agents whose heartbeat has expired, and forces them OFFLINE
6. **Skill Set Management** — Maintains Redis sorted sets per skill, adding/removing agents as they go AVAILABLE/BUSY/OFFLINE
7. **Event Publishing** — Publishes `AGENT_AVAILABLE`, `AGENT_BUSY`, `AGENT_OFFLINE`, `AGENT_DISCONNECTED` events to Kafka
8. **Cross-Service State Sync** — Consumes `routing-events` and `call-lifecycle-events` to keep agent state in sync with routing decisions and call completions

---

## 4. APIs Exposed

| Endpoint | Method | Purpose |
|---|---|---|
| `POST /api/v1/agents/internal` | POST | Create agent profile (called by user-service, protected by `X-Internal-Key`) |
| `POST /api/v1/agents/{agentId}/login` | POST | Transition OFFLINE → AVAILABLE |
| `POST /api/v1/agents/{agentId}/logout` | POST | Transition AVAILABLE → OFFLINE |
| `POST /api/v1/agents/{agentId}/available` | POST | Transition BUSY → AVAILABLE |
| `POST /api/v1/agents/{agentId}/busy` | POST | Transition AVAILABLE → BUSY |
| `POST /api/v1/agents/{agentId}/heartbeat` | POST | Update heartbeat timestamp |
| `GET /api/v1/agents/{agentId}/state` | GET | Return current agent state (status, activeCallId) |
| `GET /api/v1/agents/counts` | GET | Return agent counts by status for a tenant |

### Internal Endpoint Security
The `/internal` endpoint is protected by a shared secret (`X-Internal-Key` header). The API Gateway blocks external access to this path with a 403 route.

---

## 5. Kafka Usage

### Produces → `agent-events`
| Event Type | When |
|---|---|
| `AGENT_AVAILABLE` | Agent logs in or call completes |
| `AGENT_BUSY` | Call assigned to agent |
| `AGENT_OFFLINE` | Agent logs out manually |
| `AGENT_DISCONNECTED` | Heartbeat timeout detected |

### Consumes ← `routing-events`
- **Handler:** `RoutingEventConsumer` → `handleRoutingEvent()`
- **Purpose:** When a call is ASSIGNED to this agent, update the agent's status to BUSY and store the `activeCallId`
- **Fix Applied:** Now ignores ASSIGNED events if the agent is OFFLINE (prevents ping-pong loop)

### Consumes ← `call-lifecycle-events`
- **Handler:** `CallLifecycleConsumer` → `handleCallCompletion()`
- **Purpose:** When a call is COMPLETED, transition the agent back to AVAILABLE and clear the `activeCallId`

---

## 6. Redis Usage

| Key Pattern | Type | Purpose |
|---|---|---|
| `tenant:{id}:agent:{id}:state` | Hash | `{status, lastAssignedTime}` — fast lookup for routing decisions |
| `tenant:{id}:skill:{skill}:available` | Sorted Set | Members = agent IDs, Score = `lastAssignedTime` (LRU scheduling). The routing engine's Lua script reads these sets |
| `tenant:{id}:agent:{id}:heartbeat` | String | Epoch timestamp, TTL = 30s. If this key expires, the agent is considered disconnected |

### When keys are created/updated/deleted:
- **Login (OFFLINE → AVAILABLE):** Creates state hash, adds agent to all skill sorted sets, creates heartbeat key
- **Call assigned (AVAILABLE → BUSY):** Updates state hash to BUSY, removes agent from all skill sorted sets
- **Call completed (BUSY → AVAILABLE):** Updates state hash to AVAILABLE, re-adds agent to skill sorted sets
- **Logout/Disconnect (→ OFFLINE):** Deletes state hash, removes from skill sorted sets, deletes heartbeat key

---

## 7. PostgreSQL Usage

### Database: `minigenesys_agent_state`

### Table: `agents`
| Column | Type | Description |
|---|---|---|
| `id` | VARCHAR (PK) | Agent ID (e.g., "AG_001") — set by user-service, not auto-generated |
| `tenant_id` | VARCHAR | Multi-tenant scope |
| `name` | VARCHAR | Agent display name |
| `status` | ENUM (AVAILABLE, BUSY, OFFLINE) | Current state |
| `last_assigned_time` | BIGINT | Epoch ms of last call assignment (used for LRU) |
| `last_heartbeat_at` | BIGINT | Epoch ms of last heartbeat received |
| `active_call_id` | VARCHAR (nullable) | Currently assigned call ID |
| `created_at` | TIMESTAMP | Auto-set |
| `updated_at` | TIMESTAMP | Auto-set |

### Table: `agent_skills` (ElementCollection)
| Column | Type | Description |
|---|---|---|
| `agent_id` | VARCHAR (FK) | Links to agents.id |
| `skill` | VARCHAR | Skill name (e.g., "sales", "support") |

---

## 8. Important State Changes

### State Machine
```
                    ┌─── call assigned ───┐
                    │                     ▼
  [OFFLINE] ──login──► [AVAILABLE] ──────► [BUSY]
      ▲                     │                │
      │                     │                │
      └── logout ───────────┘                │
      └── heartbeat timeout ────────────────┘
                                    (+ requeue call)
```

### Allowed Transitions
| From | To | Trigger |
|---|---|---|
| OFFLINE → AVAILABLE | Agent clicks "Start Shift" (login endpoint) |
| AVAILABLE → BUSY | Routing service assigns a call (via Kafka) |
| BUSY → AVAILABLE | Call completed (via Kafka) |
| AVAILABLE → OFFLINE | Agent clicks "End Shift" (logout endpoint) |
| BUSY → OFFLINE | Heartbeat timeout only (detectDisconnects) |

### Blocked Transitions
| From | To | Why |
|---|---|---|
| OFFLINE → BUSY | Invalid — must go through AVAILABLE first |
| BUSY → OFFLINE (via API) | Blocked — agents can't logout while on a call. Only the heartbeat timeout can force this |

---

## 9. Interaction With Other Services

| Direction | Service | How | Why |
|---|---|---|---|
| **Called by ←** | User Service | REST `POST /internal` | Agent profile provisioning during account creation |
| **Called by ←** | API Gateway | HTTP proxy | All external agent state requests |
| **Consumes ←** | Routing Service | Kafka `routing-events` | Sync agent to BUSY when call is assigned |
| **Consumes ←** | Call Service | Kafka `call-lifecycle-events` | Sync agent to AVAILABLE when call completes |
| **Produces →** | Kafka `agent-events` | All state changes | Consumed by call-service (recovery), websocket-gateway (UI push), analytics, audit |

---

## 10. Edge Cases / Failure Scenarios

| Scenario | What Happens |
|---|---|
| **F5 refresh during active call** | Heartbeat gap hits 30s → `detectDisconnects()` forces OFFLINE → call-service requeues the call → routing-service tries to re-assign (now protected by idempotency fix) |
| **Agent tries to logout while on call** | API returns `409 CONFLICT: Invalid state transition from BUSY to OFFLINE`. UI disables the End Shift button |
| **Duplicate heartbeat** | Harmless — just updates the timestamp |
| **Heartbeat on OFFLINE agent** | Returns `409 CONFLICT: Agent is OFFLINE. Please login first.` |
| **Routing event arrives for OFFLINE agent** | New fix: `handleRoutingEvent()` logs a warning and returns without changing state |
| **Redis down** | State changes fail, routing breaks. PostgreSQL still has the durable record but real-time routing is impossible |
| **Two routing events arrive for the same agent simultaneously** | Second event sees agent is already BUSY, writes BUSY→BUSY (harmless idempotent update) |

---

## 11. Interview Explanation

> "The agent-state-service is the real-time availability engine for the contact center. It maintains a dual-write architecture — every agent state change is written to both Redis (for sub-millisecond routing lookups) and PostgreSQL (for durability and audit). Redis stores agent states as hashes and maintains sorted sets per skill where the score is the agent's last-assigned time, enabling LRU-based fair scheduling. The service runs a heartbeat-based disconnect detector every 10 seconds — if an agent's browser hasn't sent a ping in 30 seconds, the system assumes they crashed and automatically marks them offline, triggering call recovery. I also debugged and fixed a critical ping-pong loop where the heartbeat timeout would cascade into an infinite assign-disconnect-requeue cycle because the routing service's idempotency cache blindly re-assigned calls to offline agents."

---

## 12. Annotated Flow Traces (Exact Methods)

Key constants in `AgentStateService`:
```java
AGENT_STATE_KEY_TPL = "tenant:%s:agent:%s:state"        // Hash
SKILL_KEY_TPL       = "tenant:%s:skill:%s:available"    // Sorted Set
HEARTBEAT_KEY_TPL  = "tenant:%s:agent:%s:heartbeat"    // String
HEARTBEAT_TIMEOUT_MS = 30000
```

### Entry 1: Agent Login / Available / Logout / Busy
**Controller:** `AgentStateController.login()` / `available()` / `logout()` / `busy()`
**All call:** `AgentStateService.changeState(tenantId, agentId, newStatus)`

```
changeState(tenantId, agentId, AVAILABLE):
  → agentRepository.findByIdAndTenantId(agentId, tenantId)  [PG SELECT]
  → isValidTransition(oldStatus, AVAILABLE)?  [truth table below]
      if false → throw 409 CONFLICT
  → agent.setStatus(AVAILABLE)
  → agentRepository.save(agent)  [PG UPDATE]
  → updateRedisState(agent, oldStatus, AVAILABLE)
      opsForHash().put(stateKey, "status", "AVAILABLE")
      opsForHash().put(stateKey, "lastAssignedTime", ...)
      for each skill:
        opsForZSet().add("tenant:X:skill:sales:available", agentId, score)
  → publishEvent(agent, oldStatus, AVAILABLE, "AGENT_AVAILABLE")
      AgentEventProducer.publishAgentEvent(AgentEvent)
      → kafkaTemplate.send("agent-events", tenantId, message)
```

**`isValidTransition()` truth table (from source code lines 218-232):**

| From | To | Result |
|---|---|---|
| OFFLINE | AVAILABLE | ✅ allowed |
| AVAILABLE | BUSY | ✅ allowed |
| BUSY | AVAILABLE | ✅ allowed |
| AVAILABLE | OFFLINE | ✅ allowed |
| BUSY | OFFLINE | ✅ allowed (forced disconnect) |
| OFFLINE | BUSY | ❌ blocked |
| same | same | ❌ blocked |

### Entry 2: Heartbeat
**Controller:** `AgentStateController.heartbeat()`
**Calls:** `AgentStateService.handleHeartbeat(tenantId, agentId)`

```
handleHeartbeat(tenantId, agentId):
  → agentRepository.findByIdAndTenantId(agentId, tenantId)  [PG SELECT]
  → if agent.status == OFFLINE → throw 409 CONFLICT
  → agent.setLastHeartbeatAt(Instant.now().toEpochMilli())
  → agentRepository.save(agent)  [PG UPDATE: last_heartbeat_at]
  → heartbeatKey = "tenant:%s:agent:%s:heartbeat"
  → redisTemplate.opsForValue().set(heartbeatKey, epochMs, 30000, MILLISECONDS)
    (resets the 30-second countdown every call)
```

### Entry 3: Disconnect Detection (Scheduled)
**Method:** `AgentStateService.detectDisconnects()` — `@Scheduled(fixedRateString="${agent.heartbeat.scan-interval:10000}")`

```
detectDisconnects() [runs every 10s]:
  → threshold = now() - 30000ms
  → agentRepository.findByStatusInAndLastHeartbeatAtBefore([AVAILABLE,BUSY], threshold)
     [PG: SELECT * FROM agents WHERE status IN ('AVAILABLE','BUSY') AND last_heartbeat_at < threshold]
  → agentRepository.findByStatusInAndLastHeartbeatAtIsNull([AVAILABLE,BUSY])
     [catches agents that never sent a heartbeat]
  → for each expired agent:
      agent.setStatus(OFFLINE)
      agent.setActiveCallId(null)
      agentRepository.save(agent)  [PG UPDATE]
      updateRedisState(agent, oldStatus, OFFLINE)
        opsForHash().put(stateKey, "status", "OFFLINE")
        for each skill: opsForZSet().remove(skillKey, agentId)
        redisTemplate.delete(stateKey)  ← destroys the hash entirely
      publishEvent(agent, oldStatus, OFFLINE, "AGENT_DISCONNECTED")
        → Kafka: agent-events
          → AgentEventConsumer (call-service):
              CallService.handleAgentDisconnect()  ← requeues orphaned calls
          → KafkaEventConsumer (websocket-gateway): pushes to browser
      redisTemplate.delete(heartbeatKey)  ← explicit cleanup
```

### Entry 4: Kafka Consumer — `routing-events`
**Consumer class:** `RoutingEventConsumer` (group: `agent-state-service-group`)
**Calls:** `AgentStateService.handleRoutingEvent(RoutingEvent event)`

```
handleRoutingEvent(event):
  → if event.status != "ASSIGNED" or event.agentId == null → return (skip)
  → agentRepository.findByIdAndTenantId(event.agentId, event.tenantId)  [PG SELECT]
  → if agent not found → log warn, return
  → if agent.status == OFFLINE:
      log.warn("Ignoring routing event... Breaking the ping-pong loop.")
      return  ← THE CRITICAL BUG FIX
  → agent.setStatus(BUSY)
  → agent.setActiveCallId(event.callId)
  → agent.setLastAssignedTime(now())
  → agentRepository.save(agent)  [PG UPDATE]
  → updateRedisState(agent, AVAILABLE, BUSY)
      opsForHash().put(stateKey, "status", "BUSY")
      opsForHash().put(stateKey, "lastAssignedTime", now)
      for each skill: opsForZSet().remove(skillKey, agentId)
  → publishEvent(agent, AVAILABLE, BUSY, "AGENT_BUSY")
      → Kafka: agent-events → websocket-gateway → browser shows "On Call"
```

### Entry 5: Kafka Consumer — `call-lifecycle-events`
**Consumer class:** `CallLifecycleConsumer` (group: `agent-state-service-group`)
**Calls:** `AgentStateService.handleCallCompletion(CallLifecycleEvent event)`

```
handleCallCompletion(event):
  → agentRepository.findByIdAndTenantId(event.agentId, event.tenantId)  [PG SELECT]
  → if not found → log warn, return
  → agent.setStatus(AVAILABLE)
  → agent.setActiveCallId(null)
  → agentRepository.save(agent)  [PG UPDATE]
  → updateRedisState(agent, BUSY, AVAILABLE)
      opsForHash().put(stateKey, "status", "AVAILABLE")
      for each skill: opsForZSet().add(skillKey, agentId, newScore)
        ← agent re-enters routing pool with fresh score (goes to back of queue)
  → publishEvent(agent, BUSY, AVAILABLE, "AGENT_AVAILABLE")
      → Kafka: agent-events → websocket-gateway → browser shows "Ready"
```

