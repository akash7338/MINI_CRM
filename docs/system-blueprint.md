# Mini Genesys — System Blueprint

---

## 1. Services

### 1.1 API Gateway
- Ingress for all external HTTP/WebSocket traffic
- JWT validation + tenant extraction (`tenantId` from token claim)
- Rate limiting per tenant
- Route to downstream services via reverse proxy
- Centralized request logging + trace propagation (Jaeger)

### 1.2 User Service
- Tenant and user account management (CRUD)
- Authentication: issue JWT tokens
- Role management: `AGENT`, `SUPERVISOR`, `ADMIN`
- Password hashing (BCrypt)
- Multi-tenant isolation: all queries scoped by `tenantId`

### 1.3 Agent State Service
- Maintains agent real-time status: `AVAILABLE`, `BUSY`, `OFFLINE`
- Reads/writes agent state to Redis (primary) and PostgreSQL (audit log)
- Exposes state query APIs for routing and dashboards
- Publishes `agent-events` to Kafka on every state change
- Handles agent heartbeat / disconnect detection

### 1.4 Call Service
- Ingests incoming call events (simulated, no SIP)
- Creates call records in PostgreSQL
- Manages call lifecycle: `CREATED → QUEUED → ROUTED → IN_PROGRESS → COMPLETED`
- Publishes `call-events` to Kafka
- Exposes call status query API

### 1.5 Routing Service
- Consumes `call-events` topic (event: `CALL_QUEUED`)
- Implements routing algorithm:
  - Filter agents by `requiredSkills ∩ agent.skills`
  - Filter by `status == AVAILABLE`
  - Sort by `lastAssignedTime ASC`, then `priority DESC`
  - Select top candidate
- Writes `Assignment` record to PostgreSQL
- Calls Agent State Service (sync) to set agent → `BUSY`
- Calls Call Service (sync) to set call → `ROUTED`
- Publishes `routing-events` (event: `CALL_ASSIGNED`)
- Handles no-agent-available: re-enqueue with backoff

### 1.6 Event Service
- Acts as Kafka consumer aggregator and fan-out
- Normalizes events across all topics into unified schema
- Publishes to `analytics-events` topic
- Maintains DLQ consumers for all topics
- Implements idempotency via `eventId` deduplication in Redis

### 1.7 Analytics Service
- Consumes `analytics-events` topic
- Aggregates metrics: queue depth, AHT, agent utilization, SLA breach
- Writes aggregated data to PostgreSQL (time-series friendly schema)
- Exposes REST API for dashboard data queries
- Multi-tenant scoped; no cross-tenant data leak

### 1.8 WebSocket Gateway
- Maintains persistent WebSocket connections per agent/supervisor session
- Subscribes to Redis Pub/Sub channels per tenant
- Pushes real-time updates to connected clients (<200ms SLA)
- Topics pushed: agent state changes, call queue updates
- Horizontally scalable via shared Redis Pub/Sub

---

## 2. API Contracts

### 2.1 User Service

#### POST /api/v1/auth/login
- **Method**: POST
- **Request**:
  ```json
  { "email": "string", "password": "string", "tenantId": "uuid" }
  ```
- **Response**:
  ```json
  { "accessToken": "string (JWT)", "refreshToken": "string", "expiresIn": 3600 }
  ```

#### POST /api/v1/users
- **Method**: POST
- **Request**:
  ```json
  {
    "tenantId": "uuid", "email": "string", "password": "string",
    "role": "AGENT | SUPERVISOR | ADMIN", "skills": ["string"]
  }
  ```
- **Response**:
  ```json
  { "userId": "uuid", "email": "string", "role": "string", "createdAt": "ISO8601" }
  ```

#### GET /api/v1/users/{userId}
- **Method**: GET
- **Request**: Path: `userId`, Header: `X-Tenant-Id: uuid`
- **Response**:
  ```json
  { "userId": "uuid", "email": "string", "role": "string", "skills": ["string"], "tenantId": "uuid" }
  ```

---

### 2.2 Agent State Service

#### GET /api/v1/agents/{agentId}/state
- **Method**: GET
- **Request**: Path: `agentId`, Header: `X-Tenant-Id: uuid`
- **Response**:
  ```json
  { "agentId": "uuid", "tenantId": "uuid", "status": "AVAILABLE | BUSY | OFFLINE", "lastAssignedTime": "ISO8601" }
  ```

#### PUT /api/v1/agents/{agentId}/state
- **Method**: PUT
- **Request**:
  ```json
  { "status": "AVAILABLE | OFFLINE" }
  ```
- **Response**:
  ```json
  { "agentId": "uuid", "status": "string", "updatedAt": "ISO8601" }
  ```

#### GET /api/v1/agents?tenantId={tenantId}&status={status}&skills={skills}
- **Method**: GET
- **Request**: Query: `tenantId`, `status` (optional), `skills` (comma-separated, optional)
- **Response**:
  ```json
  {
    "agents": [
      { "agentId": "uuid", "status": "string", "skills": ["string"], "lastAssignedTime": "ISO8601" }
    ]
  }
  ```

---

### 2.3 Call Service

#### POST /api/v1/calls
- **Method**: POST
- **Request**:
  ```json
  {
    "tenantId": "uuid", "callerId": "string",
    "requiredSkills": ["string"], "priority": 1
  }
  ```
- **Response**:
  ```json
  { "callId": "uuid", "status": "CREATED", "createdAt": "ISO8601" }
  ```

#### GET /api/v1/calls/{callId}
- **Method**: GET
- **Request**: Path: `callId`, Header: `X-Tenant-Id: uuid`
- **Response**:
  ```json
  {
    "callId": "uuid", "tenantId": "uuid", "status": "string",
    "requiredSkills": ["string"], "priority": 1,
    "agentId": "uuid | null", "createdAt": "ISO8601", "updatedAt": "ISO8601"
  }
  ```

#### PUT /api/v1/calls/{callId}/status
- **Method**: PUT
- **Request**:
  ```json
  { "status": "IN_PROGRESS | COMPLETED" }
  ```
- **Response**:
  ```json
  { "callId": "uuid", "status": "string", "updatedAt": "ISO8601" }
  ```

#### GET /api/v1/calls?tenantId={tenantId}&status={status}
- **Method**: GET
- **Response**:
  ```json
  { "calls": [ { "callId": "uuid", "status": "string", "priority": 1, "requiredSkills": ["string"] } ] }
  ```

---

### 2.4 Routing Service

#### POST /api/v1/routing/assign (internal only)
- **Method**: POST
- **Request**:
  ```json
  { "callId": "uuid", "tenantId": "uuid" }
  ```
- **Response**:
  ```json
  { "callId": "uuid", "agentId": "uuid", "assignedAt": "ISO8601" }
  ```
- **Error (no agent)**:
  ```json
  { "error": "NO_AGENT_AVAILABLE", "retryAfterMs": 5000 }
  ```

---

### 2.5 Analytics Service

#### GET /api/v1/analytics/queue?tenantId={tenantId}
- **Method**: GET
- **Response**:
  ```json
  { "tenantId": "uuid", "queueDepth": 42, "avgWaitMs": 12000, "slaBreach": 3 }
  ```

#### GET /api/v1/analytics/agents?tenantId={tenantId}
- **Method**: GET
- **Response**:
  ```json
  {
    "tenantId": "uuid",
    "totalAgents": 100, "available": 60, "busy": 35, "offline": 5,
    "utilizationPct": 35.0
  }
  ```

#### GET /api/v1/analytics/calls?tenantId={tenantId}&from={ISO8601}&to={ISO8601}
- **Method**: GET
- **Response**:
  ```json
  { "totalCalls": 500, "completed": 480, "avgHandleTimeMs": 240000, "abandoned": 20 }
  ```

---

### 2.6 WebSocket Gateway

#### WS /ws/agent?token={JWT}
- **Direction**: Server → Client
- **Messages pushed**:
  ```json
  { "type": "AGENT_STATE_UPDATE", "agentId": "uuid", "status": "string", "timestamp": "ISO8601" }
  { "type": "CALL_ASSIGNED", "callId": "uuid", "agentId": "uuid", "timestamp": "ISO8601" }
  ```

#### WS /ws/supervisor?token={JWT}
- **Direction**: Server → Client
- **Messages pushed**:
  ```json
  { "type": "QUEUE_UPDATE", "tenantId": "uuid", "queueDepth": 42, "timestamp": "ISO8601" }
  { "type": "AGENT_STATE_UPDATE", "agentId": "uuid", "status": "string", "timestamp": "ISO8601" }
  ```

---

## 3. Event Contracts (Kafka)

### 3.1 Topic: `call-events`
- **Producer**: Call Service
- **Consumers**: Routing Service, Event Service
- **Partition key**: `tenantId`
- **Schema**:
  ```json
  {
    "eventId": "uuid",
    "eventType": "CALL_CREATED | CALL_QUEUED | CALL_ROUTED | CALL_IN_PROGRESS | CALL_COMPLETED",
    "callId": "uuid",
    "tenantId": "uuid",
    "callerId": "string",
    "requiredSkills": ["string"],
    "priority": 1,
    "agentId": "uuid | null",
    "timestamp": "ISO8601"
  }
  ```

### 3.2 Topic: `agent-events`
- **Producer**: Agent State Service
- **Consumers**: Event Service, WebSocket Gateway
- **Partition key**: `tenantId`
- **Schema**:
  ```json
  {
    "eventId": "uuid",
    "eventType": "AGENT_AVAILABLE | AGENT_BUSY | AGENT_OFFLINE | AGENT_DISCONNECTED",
    "agentId": "uuid",
    "tenantId": "uuid",
    "previousStatus": "string",
    "newStatus": "string",
    "timestamp": "ISO8601"
  }
  ```

### 3.3 Topic: `routing-events`
- **Producer**: Routing Service
- **Consumers**: Event Service, WebSocket Gateway
- **Partition key**: `tenantId`
- **Schema**:
  ```json
  {
    "eventId": "uuid",
    "eventType": "CALL_ASSIGNED | ROUTING_FAILED",
    "callId": "uuid",
    "agentId": "uuid | null",
    "tenantId": "uuid",
    "failureReason": "NO_AGENT_AVAILABLE | null",
    "retryCount": 0,
    "timestamp": "ISO8601"
  }
  ```

### 3.4 Topic: `analytics-events`
- **Producer**: Event Service
- **Consumers**: Analytics Service
- **Partition key**: `tenantId`
- **Schema**:
  ```json
  {
    "eventId": "uuid",
    "sourceEventId": "uuid",
    "sourceTopic": "call-events | agent-events | routing-events",
    "eventType": "string",
    "tenantId": "uuid",
    "payload": { },
    "timestamp": "ISO8601"
  }
  ```

### 3.5 Dead Letter Queues (DLQ)
- `call-events.DLQ`
- `agent-events.DLQ`
- `routing-events.DLQ`
- `analytics-events.DLQ`
- **Retry policy**: 3 attempts, exponential backoff (1s, 5s, 30s)
- **Deduplication**: `eventId` checked in Redis before processing

---

## 4. Data Models

### 4.1 User Service — PostgreSQL

#### Table: `tenants`
| Field | Type | Constraints |
|-------|------|-------------|
| `id` | UUID | PK |
| `name` | VARCHAR(255) | NOT NULL |
| `domain` | VARCHAR(255) | UNIQUE |
| `createdAt` | TIMESTAMPTZ | NOT NULL |
| `isActive` | BOOLEAN | DEFAULT true |

#### Table: `users`
| Field | Type | Constraints |
|-------|------|-------------|
| `id` | UUID | PK |
| `tenantId` | UUID | FK → tenants.id, NOT NULL |
| `email` | VARCHAR(255) | UNIQUE per tenant |
| `passwordHash` | VARCHAR(255) | NOT NULL |
| `role` | ENUM(AGENT, SUPERVISOR, ADMIN) | NOT NULL |
| `createdAt` | TIMESTAMPTZ | NOT NULL |
| `isActive` | BOOLEAN | DEFAULT true |

#### Table: `user_skills`
| Field | Type | Constraints |
|-------|------|-------------|
| `userId` | UUID | FK → users.id |
| `skill` | VARCHAR(100) | NOT NULL |
| PK | (`userId`, `skill`) | |

---

### 4.2 Agent State Service — PostgreSQL + Redis

#### Table: `agents`
| Field | Type | Constraints |
|-------|------|-------------|
| `id` | UUID | PK (same as users.id) |
| `tenantId` | UUID | NOT NULL, indexed |
| `status` | ENUM(AVAILABLE, BUSY, OFFLINE) | NOT NULL |
| `lastAssignedTime` | TIMESTAMPTZ | |
| `updatedAt` | TIMESTAMPTZ | NOT NULL |

#### Table: `agent_state_audit`
| Field | Type | Constraints |
|-------|------|-------------|
| `id` | UUID | PK |
| `agentId` | UUID | FK → agents.id |
| `tenantId` | UUID | NOT NULL |
| `previousStatus` | VARCHAR(20) | |
| `newStatus` | VARCHAR(20) | |
| `changedAt` | TIMESTAMPTZ | NOT NULL |
| `reason` | VARCHAR(255) | |

---

### 4.3 Call Service — PostgreSQL

#### Table: `calls`
| Field | Type | Constraints |
|-------|------|-------------|
| `id` | UUID | PK |
| `tenantId` | UUID | NOT NULL, indexed |
| `callerId` | VARCHAR(255) | NOT NULL |
| `status` | ENUM(CREATED, QUEUED, ROUTED, IN_PROGRESS, COMPLETED) | NOT NULL |
| `priority` | SMALLINT | DEFAULT 1 |
| `agentId` | UUID | nullable |
| `createdAt` | TIMESTAMPTZ | NOT NULL |
| `updatedAt` | TIMESTAMPTZ | NOT NULL |
| `completedAt` | TIMESTAMPTZ | |

#### Table: `call_required_skills`
| Field | Type | Constraints |
|-------|------|-------------|
| `callId` | UUID | FK → calls.id |
| `skill` | VARCHAR(100) | NOT NULL |
| PK | (`callId`, `skill`) | |

---

### 4.4 Routing Service — PostgreSQL

#### Table: `assignments`
| Field | Type | Constraints |
|-------|------|-------------|
| `id` | UUID | PK |
| `callId` | UUID | UNIQUE, FK → calls.id |
| `agentId` | UUID | NOT NULL |
| `tenantId` | UUID | NOT NULL |
| `assignedAt` | TIMESTAMPTZ | NOT NULL |
| `completedAt` | TIMESTAMPTZ | |

---

### 4.5 Analytics Service — PostgreSQL

#### Table: `call_metrics`
| Field | Type | Constraints |
|-------|------|-------------|
| `id` | UUID | PK |
| `tenantId` | UUID | NOT NULL, indexed |
| `callId` | UUID | NOT NULL |
| `queueWaitMs` | BIGINT | |
| `handleTimeMs` | BIGINT | |
| `status` | VARCHAR(20) | |
| `recordedAt` | TIMESTAMPTZ | NOT NULL |

#### Table: `agent_metrics`
| Field | Type | Constraints |
|-------|------|-------------|
| `id` | UUID | PK |
| `tenantId` | UUID | NOT NULL, indexed |
| `agentId` | UUID | NOT NULL |
| `busyDurationMs` | BIGINT | |
| `callsHandled` | INT | |
| `windowStart` | TIMESTAMPTZ | NOT NULL |
| `windowEnd` | TIMESTAMPTZ | NOT NULL |

---

## 5. State Management

### 5.1 Redis Structures

#### Agent State (per agent)
- **Key**: `tenant:{tenantId}:agent:{agentId}:state`
- **Type**: Hash
- **Fields**:
  - `status` → `AVAILABLE | BUSY | OFFLINE`
  - `lastAssignedTime` → epoch ms
  - `skills` → comma-separated string
- **TTL**: None (evict on OFFLINE; heartbeat resets)

#### Available Agent Set (per tenant, per skill)
- **Key**: `tenant:{tenantId}:skill:{skill}:available`
- **Type**: Sorted Set
- **Score**: `lastAssignedTime` (epoch ms, ascending = least recently used)
- **Member**: `agentId`

#### Call Queue (per tenant)
- **Key**: `tenant:{tenantId}:call:queue`
- **Type**: Sorted Set
- **Score**: `-priority` (higher priority = lower score for ZPOPMIN) + `createdAt` tiebreak
- **Member**: `callId`

#### Event Idempotency
- **Key**: `event:dedup:{eventId}`
- **Type**: String (`"1"`)
- **TTL**: 86400s (24h)

#### Agent Heartbeat
- **Key**: `tenant:{tenantId}:agent:{agentId}:heartbeat`
- **Type**: String (timestamp)
- **TTL**: 30s (agent must renew via keepalive; expiry triggers OFFLINE transition)

#### WebSocket Pub/Sub Channels
- **Agent updates**: `channel:tenant:{tenantId}:agent-updates`
- **Queue updates**: `channel:tenant:{tenantId}:queue-updates`
- **Type**: Redis Pub/Sub

#### Session / JWT Blacklist
- **Key**: `auth:blacklist:{jti}`
- **Type**: String (`"revoked"`)
- **TTL**: Remaining token lifetime

### 5.2 Redis Clustering
- 6-node cluster (3 primary + 3 replica)
- Hash slot distribution by `tenantId` prefix
- Tenant namespace isolation via key prefix: `tenant:{tenantId}:*`

---

## 6. Inter-Service Communication

### 6.1 Synchronous (REST / HTTP)

| Caller | Callee | Endpoint | Purpose |
|--------|--------|----------|---------|
| API Gateway | All Services | Proxy | External request routing |
| Routing Service | Agent State Service | `PUT /api/v1/agents/{id}/state` | Set agent → BUSY |
| Routing Service | Call Service | `PUT /api/v1/calls/{id}/status` | Set call → ROUTED |
| WebSocket Gateway | User Service | `GET /api/v1/users/{id}` (JWT validate) | Token verification |
| Analytics Service | — | — | Reads only from own DB |

### 6.2 Asynchronous (Kafka)

| Producer | Topic | Consumers |
|----------|-------|-----------|
| Call Service | `call-events` | Routing Service, Event Service |
| Agent State Service | `agent-events` | Event Service, WebSocket Gateway |
| Routing Service | `routing-events` | Event Service, WebSocket Gateway |
| Event Service | `analytics-events` | Analytics Service |

### 6.3 Pub/Sub (Redis)

| Publisher | Channel | Subscribers |
|-----------|---------|-------------|
| Agent State Service | `tenant:{id}:agent-updates` | WebSocket Gateway |
| Routing Service | `tenant:{id}:queue-updates` | WebSocket Gateway |

### 6.4 Communication Rules
- All sync calls go through API Gateway (external); direct service-to-service (internal) bypasses gateway
- Internal service mesh uses Kubernetes DNS: `http://agent-state-service:8080`
- All calls carry `X-Tenant-Id`, `X-Trace-Id` (Jaeger) headers
- Circuit breakers on all sync calls (Resilience4j: 50% failure threshold, 10s wait)
- Internal HTTP timeout: 2000ms
- No service calls Analytics Service directly (read-only from own DB)

---

## 7. Sequence Flows

### Call Lifecycle

```
1.  Client          → POST /api/v1/calls               → API Gateway
2.  API Gateway     → validate JWT, extract tenantId   → Call Service
3.  Call Service    → INSERT calls (status=CREATED)    → PostgreSQL
4.  Call Service    → ZADD call:queue (priority score) → Redis
5.  Call Service    → publish CALL_CREATED             → Kafka:call-events
6.  Call Service    → UPDATE status=QUEUED             → PostgreSQL
7.  Call Service    → publish CALL_QUEUED              → Kafka:call-events
8.  Routing Service → consume CALL_QUEUED              ← Kafka:call-events
9.  Routing Service → ZRANGEBYSCORE available agents   → Redis
10. Routing Service → select best agent (skills+LRU)   → (in-memory)
11. Routing Service → PUT agent status=BUSY            → Agent State Service
12. Agent State Svc → HSET agent state=BUSY            → Redis
13. Agent State Svc → UPDATE agents (status=BUSY)      → PostgreSQL
14. Agent State Svc → publish AGENT_BUSY               → Kafka:agent-events
15. Routing Service → PUT call status=ROUTED           → Call Service
16. Call Service    → UPDATE calls (status=ROUTED)     → PostgreSQL
17. Call Service    → publish CALL_ROUTED              → Kafka:call-events
18. Routing Service → INSERT assignments               → PostgreSQL
19. Routing Service → publish CALL_ASSIGNED            → Kafka:routing-events
20. WebSocket GW    → consume CALL_ASSIGNED            ← Kafka:routing-events
21. WebSocket GW    → PUBLISH to Redis channel         → Redis Pub/Sub
22. WebSocket GW    → push CALL_ASSIGNED to agent WS   → Agent Browser
23. Event Service   → consume all events               ← Kafka (all topics)
24. Event Service   → publish normalized events        → Kafka:analytics-events
25. Analytics Svc   → consume + aggregate              ← Kafka:analytics-events
26. Analytics Svc   → INSERT call_metrics              → PostgreSQL
```

---

### Routing Flow

```
1.  Routing Service receives CALL_QUEUED event (callId, tenantId, requiredSkills, priority)
2.  Check event idempotency:
      → GET event:dedup:{eventId} from Redis
      → If exists: discard (already processed)
      → If not: SET event:dedup:{eventId} = 1, TTL 86400s
3.  For each required skill:
      → ZRANGE tenant:{tenantId}:skill:{skill}:available 0 -1 WITHSCORES
4.  Intersect agent sets across all skills → candidate pool
5.  Sort candidates by score ascending (lowest lastAssignedTime = LRU)
6.  Select agent[0] from sorted candidates
7.  If candidate pool empty:
      → Publish ROUTING_FAILED to routing-events
      → Schedule retry: re-enqueue callId after backoff (Fibonacci: 1s, 1s, 2s, 3s, 5s, max 30s)
      → Max retries: 10; after limit → CALL_ABANDONED
8.  Atomically (Redis MULTI/EXEC):
      → ZREM tenant:{tenantId}:skill:{skill}:available {agentId}  [all skills]
      → HSET tenant:{tenantId}:agent:{agentId}:state status BUSY lastAssignedTime {now}
9.  PUT /agents/{agentId}/state → { status: BUSY }  (Agent State Service)
10. PUT /calls/{callId}/status → { status: ROUTED }  (Call Service)
11. INSERT assignments (callId, agentId, tenantId, assignedAt)
12. Publish CALL_ASSIGNED to routing-events
```

---

### Agent State Transitions

```
States: AVAILABLE, BUSY, OFFLINE

Transitions:
  AVAILABLE → BUSY       [Trigger: Routing Service assigns call]
                         [Action: HSET Redis, UPDATE PostgreSQL, publish AGENT_BUSY]

  BUSY → AVAILABLE       [Trigger: Call COMPLETED event or agent self-report]
                         [Action: HSET Redis, ZADD available sorted sets,
                                  UPDATE PostgreSQL, publish AGENT_AVAILABLE]

  AVAILABLE → OFFLINE    [Trigger: Agent explicit logout OR heartbeat TTL expired]
                         [Action: HSET Redis, ZREM from all skill sets,
                                  UPDATE PostgreSQL, publish AGENT_OFFLINE]

  BUSY → OFFLINE         [Trigger: Agent disconnect detected (heartbeat expiry)]
                         [Action: HSET Redis, UPDATE PostgreSQL, publish AGENT_DISCONNECTED,
                                  Requeue associated call → CALL_QUEUED]

  OFFLINE → AVAILABLE    [Trigger: Agent login / reconnect]
                         [Action: HSET Redis, ZADD available sorted sets,
                                  UPDATE PostgreSQL, publish AGENT_AVAILABLE]

  OFFLINE → BUSY         [INVALID — reject]
  BUSY → BUSY            [INVALID — reject, log warning]

State Machine (text diagram):

  [OFFLINE] ──login──────────────► [AVAILABLE] ──call assigned──► [BUSY]
      ▲                                 │                            │
      │                                 │                            │
      └──── logout / heartbeat ─────────┘◄──── call completed ───────┘
                                        │
                             heartbeat TTL expiry
                             (while BUSY → OFFLINE
                              + requeue call)
```

---

## 8. Failure Handling

| Failure | Detection | Recovery |
|---------|-----------|----------|
| Agent disconnect | Heartbeat TTL expired in Redis | Set OFFLINE, requeue active call |
| Kafka consumer lag | Consumer group lag metrics | Scale consumer instances; DLQ after 3 retries |
| Redis failure | Health check + circuit breaker | Fall back to PostgreSQL for state reads |
| Duplicate events | `eventId` dedup in Redis | Discard if `event:dedup:{eventId}` exists |
| No agent available | Routing returns empty candidate pool | Fibonacci backoff re-enqueue, max 10 retries |
| Service timeout | Resilience4j circuit breaker | Fail fast; emit alert; return 503 |
| DB connection loss | HikariCP pool exhaustion | Circuit open; queue requests; alert |

---

## 9. Scaling & Performance Targets

| Metric | Target |
|--------|--------|
| Routing latency | < 200ms p99 |
| WebSocket push latency | < 200ms |
| Concurrent agents | 50,000 |
| Event throughput | 100,000 events/sec |
| Kafka partitions | 50 per topic (partition by tenantId hash) |
| Redis nodes | 6-node cluster |
| Service instances | Stateless; HPA on CPU/RPS in Kubernetes |

---

## 10. Observability

| Layer | Tool | Metrics |
|-------|------|---------|
| Metrics | Prometheus + Grafana | RPS, latency p50/p95/p99, error rate, Kafka lag, Redis hit rate |
| Tracing | Jaeger | Distributed traces via `X-Trace-Id`, span per service hop |
| Logging | Structured JSON logs | `traceId`, `tenantId`, `serviceId`, `level`, `message` |
| Alerting | Grafana Alerts | Kafka lag > 10K, routing latency > 200ms, error rate > 1% |
