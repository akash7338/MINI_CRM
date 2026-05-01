# Databases, Redis & Kafka — Complete Reference

---

## 1. PostgreSQL Databases

Each service owns its own PostgreSQL database. No cross-database queries.

### Summary Table

| Service | Database | Tables | Purpose |
|---|---|---|---|
| User Service | `minigenesys_users` | `users` | User accounts, auth, roles |
| Agent State Service | `minigenesys_agent_state` | `agents`, `agent_skills` | Agent profiles, state, skills |
| Call Service | `minigenesys_calls` | `calls`, `call_skills` | Call records, lifecycle |
| Routing Service | `minigenesys_routing` | `assignments` | Historical assignment records |
| Telephony Service | `minigenesys_telephony` | `telephony_call_sessions` | Twilio SID ↔ internal call mapping |
| Analytics Service | `minigenesys_analytics` | `tenant_metrics` | Aggregated counters per tenant |
| Audit Service | `minigenesys_audit` | `audit_events` | Immutable event log |

---

### Users DB: `minigenesys_users`

#### `users`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | Auto-generated |
| `tenant_id` | VARCHAR | Multi-tenant scope |
| `username` | VARCHAR UNIQUE | Login credential |
| `password_hash` | VARCHAR | BCrypt hash |
| `role` | ENUM (SUPERVISOR, AGENT) | Access level |
| `linked_agent_id` | VARCHAR nullable | Maps to agents.id |
| `created_at` | TIMESTAMP | Auto |
| `updated_at` | TIMESTAMP | Auto |

---

### Agent State DB: `minigenesys_agent_state`

#### `agents`
| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR PK | Set by user-service (e.g., "AG_001") |
| `tenant_id` | VARCHAR | Multi-tenant scope |
| `name` | VARCHAR | Display name |
| `status` | ENUM (AVAILABLE, BUSY, OFFLINE) | Current state |
| `last_assigned_time` | BIGINT | Epoch ms, used for LRU scoring |
| `last_heartbeat_at` | BIGINT | Epoch ms, used for disconnect detection |
| `active_call_id` | VARCHAR nullable | Currently assigned call |
| `created_at` | TIMESTAMP | Auto |
| `updated_at` | TIMESTAMP | Auto |

#### `agent_skills`
| Column | Type | Notes |
|---|---|---|
| `agent_id` | VARCHAR FK → agents.id | |
| `skill` | VARCHAR | e.g., "sales", "support" |

---

### Calls DB: `minigenesys_calls`

#### `calls`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | Auto-generated |
| `tenant_id` | VARCHAR | Multi-tenant scope |
| `status` | ENUM (CREATED, QUEUED, ROUTED, IN_PROGRESS, COMPLETED, FAILED) | Call lifecycle |
| `priority` | INTEGER | Routing priority |
| `assigned_agent_id` | VARCHAR nullable | Currently assigned agent |
| `routing_failure_reason` | VARCHAR nullable | Why routing failed |
| `created_at` | TIMESTAMP | Auto |
| `updated_at` | TIMESTAMP | Auto |

#### `call_skills`
| Column | Type | Notes |
|---|---|---|
| `call_id` | UUID FK → calls.id | |
| `skill` | VARCHAR | Required skill |

---

### Routing DB: `minigenesys_routing`

#### `assignments`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | Auto-generated |
| `call_id` | VARCHAR UNIQUE | The matched call |
| `agent_id` | VARCHAR | The matched agent |
| `tenant_id` | VARCHAR | Multi-tenant scope |
| `assigned_at` | TIMESTAMP | When the match was made |

---

### Telephony DB: `minigenesys_telephony`

#### `telephony_call_sessions`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | Auto-generated |
| `tenant_id` | VARCHAR | Multi-tenant scope |
| `twilio_call_sid` | VARCHAR UNIQUE | Twilio's external identifier |
| `internal_call_id` | VARCHAR | Maps to calls.id |
| `assigned_agent_id` | VARCHAR nullable | Set from routing events |
| `from_number` | VARCHAR | Caller's phone number |
| `to_number` | VARCHAR | Twilio number called |
| `status` | VARCHAR | Twilio status (ringing, in-progress, completed) |
| `created_at` | TIMESTAMP | Auto |
| `updated_at` | TIMESTAMP | Auto |

---

### Analytics DB: `minigenesys_analytics`

#### `tenant_metrics`
| Column | Type | Notes |
|---|---|---|
| `tenant_id` | VARCHAR PK | One row per tenant |
| `version` | BIGINT | `@Version` optimistic locking |
| `total_calls` | BIGINT | All-time total calls |
| `queued_calls` | BIGINT | Currently waiting |
| `routed_calls` | BIGINT | Successfully assigned |
| `completed_calls` | BIGINT | Finished calls |
| `abandoned_calls` | BIGINT | Max retries exceeded |
| `no_agent_events` | BIGINT | Times routing found no agent |
| `active_agents` | BIGINT | Currently AVAILABLE |
| `busy_agents` | BIGINT | Currently BUSY |
| `offline_agents` | BIGINT | Currently OFFLINE |
| `average_wait_time_ms` | DOUBLE | Rolling average |
| `wait_time_count` | BIGINT | Data points for average |
| `updated_at` | TIMESTAMP | Last update |

---

### Audit DB: `minigenesys_audit`

#### `audit_events`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | Auto-generated |
| `tenant_id` | VARCHAR nullable | Multi-tenant scope |
| `actor_user_id` | VARCHAR nullable | Who triggered it |
| `actor_role` | VARCHAR nullable | Their role |
| `entity_type` | VARCHAR nullable | CALL, AGENT, or USER |
| `entity_id` | VARCHAR nullable | Specific entity ID |
| `event_type` | VARCHAR | e.g., AGENT_AVAILABLE, ASSIGNED |
| `source_service` | VARCHAR | Which service emitted this |
| `payload_json` | TEXT | Full raw JSON payload |
| `created_at` | TIMESTAMP | Immutable, auto-set |

---

## 2. Redis Key Patterns

### Agent State Keys (Owner: agent-state-service)

| Key | Type | Created | Updated | Deleted | TTL |
|---|---|---|---|---|---|
| `tenant:{tid}:agent:{aid}:state` | Hash `{status, lastAssignedTime}` | Agent login | Every state change | Agent logout/disconnect | None |
| `tenant:{tid}:skill:{skill}:available` | Sorted Set (member=agentId, score=lastAssignedTime) | Agent login | Agent goes AVAILABLE | Agent goes BUSY/OFFLINE | None |
| `tenant:{tid}:agent:{aid}:heartbeat` | String (epoch ms) | Agent login | Every 15s heartbeat | Agent logout/disconnect | **30 seconds** |

### Routing Keys (Owner: routing-service)

| Key | Type | Created | Updated | Deleted | TTL |
|---|---|---|---|---|---|
| `routing:lock:call:{callId}` | String (lock token) | Routing starts | — | Routing ends (Lua CAS delete) | **10 seconds** |
| `routing:assignment:call:{callId}` | String (agentId) | First successful assignment | — | Agent goes offline (our fix) | **1 hour** |

### Queue Manager Keys (Owner: routing-service QueueManager)

| Key | Type | Created | Updated | Deleted | TTL |
|---|---|---|---|---|---|
| `tenant:{tid}:call:queue` | Sorted Set (member=callId, score=priority+time) | Call enqueued (NO_AGENT) | — | Call dequeued (assigned or abandoned) | None |
| `tenant:{tid}:call:{callId}` | String (JSON payload) | Call enqueued | — | Call dequeued | None |
| `tenant:{tid}:call:{callId}:retries` | String (counter) | First retry | Each retry | Call dequeued | None |
| `tenant:{tid}:call:{callId}:lastRetryAt` | String (epoch ms) | First retry | Each retry | Call dequeued | None |
| `routing:active-tenants` | Set (member=tenantId) | First call enqueued for tenant | — | Tenant queue empties | None |

### What Happens When Redis Is Cleared?

| Impact | Severity | Recovery |
|---|---|---|
| All agent states lost | **Critical** | Agents must re-login. PostgreSQL has the last known state but Redis is the real-time source of truth for routing |
| All skill sorted sets lost | **Critical** | No agent can be matched to calls until they re-login and get re-added to sorted sets |
| All heartbeat keys lost | **Medium** | `detectDisconnects()` will think all agents disconnected → mass OFFLINE events → mass call requeuing |
| All routing locks lost | **Low** | Locks have 10s TTL anyway. Brief window for duplicate routing |
| Idempotency cache lost | **Low** | Duplicate Kafka messages may cause duplicate assignments (mitigated by distributed lock) |
| Retry queue lost | **Medium** | Queued calls waiting for agents are lost. Those calls will eventually time out on the Twilio side |

---

## 3. Kafka Topics

### Topic Map

| Topic | Partition Key | Producer | Consumers |
|---|---|---|---|
| `call-events` | `tenantId` | call-service | routing-service, websocket-gateway, analytics-service, audit-service |
| `agent-events` | `tenantId` | agent-state-service | call-service (recovery), websocket-gateway, analytics-service, audit-service |
| `routing-events` | `tenantId` | routing-service | agent-state-service, call-service, telephony-service, websocket-gateway, analytics-service, audit-service |
| `call-lifecycle-events` | `tenantId` | call-service | agent-state-service, websocket-gateway, analytics-service, audit-service |
| `user-events` | `tenantId` | user-service | audit-service |
| `telephony-events` | `tenantId` | telephony-service | (no consumers currently) |

### Consumer Groups

| Group ID | Service | Topics |
|---|---|---|
| `routing-service-group` | Routing Service | `call-events` |
| `agent-state-service-group` | Agent State Service | `routing-events`, `call-lifecycle-events` |
| `call-service-group` | Call Service | `routing-events` |
| `call-service-agent-recovery-group` | Call Service | `agent-events` |
| `telephony-service-group` | Telephony Service | `routing-events` |
| `websocket-gateway-group` | WebSocket Gateway | `call-events`, `routing-events`, `agent-events`, `call-lifecycle-events` |
| `analytics-service-group` | Analytics Service | `call-events`, `routing-events`, `agent-events`, `call-lifecycle-events` |
| `audit-service-group` | Audit Service | `call-events`, `routing-events`, `agent-events`, `call-lifecycle-events`, `user-events` |

### Dead Letter Queues (DLQ)

Configured in `shared-common/KafkaConfig.java`:
- **Retry policy:** Exponential backoff starting at 1 second, max 10 seconds total elapsed time
- **DLQ naming:** `{original-topic}.DLQ` (e.g., `call-events.DLQ`)
- **DLQ partition:** Same partition as the original message
- **Trigger:** When a Kafka consumer throws a `RuntimeException`, the error handler retries with backoff, then sends to DLQ

### Event Payload Schemas

#### `call-events`
```json
{
  "callId": "uuid",
  "tenantId": "string",
  "requiredSkills": ["string"],
  "priority": 1,
  "isNew": true
}
```

#### `agent-events`
```json
{
  "eventId": "uuid",
  "eventType": "AGENT_AVAILABLE | AGENT_BUSY | AGENT_OFFLINE | AGENT_DISCONNECTED",
  "agentId": "string",
  "tenantId": "string",
  "previousStatus": "string",
  "newStatus": "string",
  "callId": "string | null",
  "timestamp": "ISO8601"
}
```

#### `routing-events`
```json
{
  "callId": "uuid",
  "tenantId": "string",
  "agentId": "string | null",
  "status": "ASSIGNED | NO_AGENT | ABANDONED | ERROR",
  "success": true,
  "message": "string | null"
}
```

#### `call-lifecycle-events`
```json
{
  "eventType": "CALL_COMPLETED",
  "callId": "uuid",
  "tenantId": "string",
  "agentId": "string"
}
```

---

## 4. Durable vs Temporary Data

| Data | Storage | Durability | What Happens If Lost |
|---|---|---|---|
| User accounts | PostgreSQL (users) | **Durable** | Users can't login. Must re-create accounts |
| Agent profiles | PostgreSQL (agents) | **Durable** | Agent state reverts to last DB snapshot |
| Agent real-time state | Redis (state hash) | **Temporary** | Must re-login to restore. PostgreSQL has backup |
| Agent skill sets | Redis (sorted sets) | **Temporary** | Agents invisible to routing until re-login |
| Heartbeat timestamps | Redis (TTL string) | **Temporary** | Mass disconnect detection fires |
| Call records | PostgreSQL (calls) | **Durable** | Permanent loss of call history |
| Assignment records | PostgreSQL (assignments) | **Durable** | Historical routing data lost |
| Routing idempotency cache | Redis (1hr TTL) | **Temporary** | Brief risk of duplicate assignments |
| Retry queue | Redis (sorted set + data) | **Temporary** | Queued calls are lost, Twilio side times out |
| Analytics counters | PostgreSQL (tenant_metrics) | **Durable** | Dashboard shows zeros until events rebuild counts |
| Audit trail | PostgreSQL (audit_events) | **Durable** | Forensic history lost permanently |
| Telephony sessions | PostgreSQL (telephony_call_sessions) | **Durable** | Active Twilio calls can't be bridged |
| Kafka messages | Kafka (disk) | **Durable** | Events lost. Consumer offsets reset |

### Key Insight
Redis is the **speed layer** — it makes routing fast but it's always rebuildable from user actions (re-login). PostgreSQL is the **truth layer** — it stores the authoritative, durable records.
