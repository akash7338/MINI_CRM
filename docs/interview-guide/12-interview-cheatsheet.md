# Interview Cheatsheet — Mini Genesys

Quick-fire answers to every question an interviewer will ask about this project. Organized by topic.

---

## 🎯 The Elevator Pitch (30 seconds)

> "I built a multi-tenant cloud contact center platform with 9 Spring Boot microservices. When a customer call comes in, `CallService.createCall()` persists it and publishes to the `call-events` Kafka topic. The routing service's `RoutingEngine.assignAgent()` runs a Redis Lua script to atomically intersect skill sorted sets, picks the LRU agent, and publishes ASSIGNED to `routing-events`. That single event triggers 6 parallel consumers — agent-state-service, call-service, telephony-service, websocket-gateway, analytics-service, and audit-service — each updating their own state. The agent's browser gets a real-time WebSocket push within 200ms. I also debugged a distributed race condition where browser refreshes caused infinite call re-assignment loops, fixed with a two-layer defense in `RoutingEngine` and `AgentStateService.handleRoutingEvent()`."

---

## 🏗️ Architecture Questions

### "Walk me through the architecture."

> 9 microservices: API Gateway (auth + routing), User Service (accounts + JWT), Agent State Service (availability + heartbeats), Call Service (call lifecycle), Routing Service (skill-matching + retries), Telephony Service (Twilio integration), WebSocket Gateway (real-time push), Analytics Service (counters), Audit Service (immutable log). They communicate via Kafka for async events and REST for synchronous calls. Redis is the hot state layer, PostgreSQL is the durable layer. Each service owns its own database.

### "Why microservices instead of a monolith?"

> 1. **Independent scaling** — the routing engine needs way more compute than the audit service
> 2. **Fault isolation** — if the analytics service crashes, call routing still works
> 3. **Team ownership** — each service has a clear boundary and can be developed independently
> 4. **Technology flexibility** — the routing engine is Redis-heavy, the audit service is PostgreSQL-heavy

### "How do the services communicate?"

> **Synchronous (REST):** Used when the caller needs an immediate response — browser → API Gateway → downstream service, and telephony-service → call-service for Twilio callbacks.
> 
> **Asynchronous (Kafka):** Used for event-driven reactions — call-service publishes `call-events`, routing-service consumes them. This decouples services and provides durability.
> 
> **WebSocket (STOMP):** Used for real-time browser updates — the WebSocket Gateway consumes Kafka events and pushes them to the browser.

### "How is multi-tenancy implemented?"

> Every piece of data is scoped by `tenantId`:
> - JWT contains `tenantId` as a claim
> - API Gateway extracts it and injects `X-Tenant-Id` header
> - Every database query is filtered by `tenantId`
> - Every Redis key is prefixed with `tenant:{tenantId}:`
> - Every Kafka message is partitioned by `tenantId`
> - WebSocket subscriptions enforce tenant isolation — users can only subscribe to their own tenant's channel

---

## 🔀 Routing Questions

### "How does the routing engine work?"

> **Exact call chain:** `KafkaMessaging.consumeCallEvent()` → `routingService.processRouting()` → `routingEngine.assignAgent()`
>
> 1. **Consume** — `KafkaMessaging` reads a `call-events` message (group: `routing-service-group`)
> 2. **Lock** — `SET routing:lock:call:{callId} {uuid} NX PX 10000` (10-second NX lock)
> 3. **Idempotency check** — `GET routing:assignment:call:{callId}` — if hit, verify agent's Redis state is not OFFLINE before trusting it
> 4. **Lua script** — `SELECT_AGENT_LUA`: ZINTERSTORE temp key across all required skill sets → ZRANGE 0 0 → ZREM agent from all skill sets → HSET agent state hash to BUSY (all atomic)
> 5. **Persist** — `routingResultRepository.save(RoutingResult)` [PG]
> 6. **Cache** — `SET routing:assignment:call:{callId} {agentId} EX 3600` (1-hour TTL)
> 7. **Publish** — `KafkaMessaging.produceRoutingEvent()` → `routing-events` topic
> 8. **Release lock** — CAS delete via Lua: `if redis.call('get',key)==value then return redis.call('del',key) end`

### "Why Redis Lua scripts?"

> The routing decision involves multiple Redis operations (read skill sets, pick agent, remove from sets, mark BUSY). These must be **atomic** — if two calls try to grab the same agent simultaneously, both would succeed with regular Redis commands. A Lua script runs as a single atomic operation on the Redis server, preventing any race condition.

### "What happens when no agent is available?"

> The call enters a **retry queue** managed by `QueueManager`:
> 1. Call is added to a Redis sorted set with a priority-based score
> 2. A `@Scheduled` `RetryProcessor` runs every 5 seconds
> 3. It uses **Fibonacci backoff** — waits 1s, 1s, 2s, 3s, 5s, 8s, 13s, 21s, 30s, 30s between retries
> 4. After 10 retries (~114 seconds), the call is marked `ABANDONED`
> 5. **Optimization:** If the highest-priority call can't find an agent, lower-priority calls are skipped (they won't find one either)

### "How does skill-based routing work?"

> Each agent has skills (e.g., ["sales", "support"]). Each call requires skills (e.g., ["sales"]). The Lua script uses `ZINTERSTORE` to intersect the sorted sets for each required skill, producing a set of agents who have ALL required skills. The agent with the lowest score (oldest `lastAssignedTime`) is selected for fairness.

### "How does the priority queue scoring work?"

> Formula: `score = (-priority × 10^13) + timestamp`
> - Higher priority → more negative score → dequeued first
> - Same priority → earlier timestamp → dequeued first (FIFO)
> - The `10^13` multiplier ensures priority always dominates over timestamp

---

## 💓 Heartbeat & Disconnect Questions

### "How do you detect agent disconnects?"

> **Exact method chain:**
> 1. Browser → `POST /api/v1/agents/{id}/heartbeat` (every 15s) → `AgentStateController.heartbeat()` → `AgentStateService.handleHeartbeat(agentId)`
> 2. `handleHeartbeat()`: `opsForValue().set(heartbeatKey, "alive", 30000, MILLISECONDS)` [Redis SETEX]
> 3. `@Scheduled` every 10s: `AgentStateService.detectDisconnects()` runs
> 4. Queries PG: `agentRepository.findByStatusInAndLastHeartbeatAtBefore(statuses, cutoff)` — finds agents whose heartbeat Redis key expired
> 5. For each disconnected agent: `changeState(agent, OFFLINE)` → PG UPDATE + Redis HSET + Kafka `AGENT_DISCONNECTED` → `agent-events`
> 6. `call-service`: `AgentEventConsumer` → `CallService.handleAgentDisconnect()` → requeues active call with `newCall: false` → `call-events`

### "Tell me about the bug you fixed."

> **The Ghost Call Re-assignment Loop:**
>
> An F5 refresh killed the heartbeat for ~30 seconds. This triggered:
> 1. `AgentStateService.detectDisconnects()` → agent marked OFFLINE
> 2. `CallService.handleAgentDisconnect()` → active call requeued with `newCall: false`
> 3. `RoutingEngine.assignAgent()` → idempotency cache hit → returned cached `AG_001` without re-checking Redis state
> 4. `AgentStateService.handleRoutingEvent()` → accepted ASSIGNED event → forced OFFLINE agent back to BUSY
> 5. `detectDisconnects()` fires again → repeat infinitely
>
> **Root cause:** `RoutingEngine` trusted the idempotency cache without validating the cached agent's current Redis status.
>
> **Fix — exact code locations:**
> - **`RoutingEngine.assignAgent()`:** If cache hit, call `redisTemplate.opsForHash().get(agentStateKey, "status")`. If `"OFFLINE"` → `DEL routing:assignment:call:{callId}` → re-run Lua script
> - **`AgentStateService.handleRoutingEvent()`:** Added guard: `if (agent.getStatus() == OFFLINE) { log.warn("Breaking ping-pong loop"); return; }`

---

## 🔒 Security Questions

### "How is authentication implemented?"

> **JWT-based stateless auth:**
> 1. User-service validates credentials against BCrypt hash in PostgreSQL
> 2. Generates a JWT with claims: `{sub: userId, tenantId, role, agentId}`
> 3. Signed with HMAC-SHA256 using a shared secret from `shared-common`
> 4. Token expires after 1 hour
> 5. API Gateway validates the JWT on every request and injects tenant/role/agent headers
> 6. WebSocket Gateway validates the JWT on the STOMP CONNECT frame

### "How is authorization (RBAC) implemented?"

> In the API Gateway's `JwtAuthenticationFilter`:
> - Agents can only access their own profile (`/agents/{ownId}`)
> - Agents are blocked from `/users/*` and `/analytics/*`
> - Supervisors have full access
> - WebSocket subscriptions enforce tenant isolation (can't subscribe to another tenant's events)

### "What about internal service-to-service security?"

> The user-service calls agent-state-service's `/internal` endpoint with a shared secret (`X-Internal-Key` header). The API Gateway blocks external access to `/internal` with a 403 route rule.

---

## 📡 Real-Time / WebSocket Questions

### "How do you push real-time updates to the browser?"

> **STOMP over SockJS — exact method chain:**
> 1. Browser → SockJS → `WebSocketConfig`: endpoint `/ws` with `.withSockJS()`, broker `/topic`, app prefix `/app`
> 2. CONNECT frame: `AuthChannelInterceptor.preSend()` → `jwtUtil.validateToken(token)` → `claims.get("tenantId")` → `sessionAttributes.put("tenantId", tenantId)` → `accessor.setUser(auth)`
> 3. SUBSCRIBE `/topic/events/tenant1`: `AuthChannelInterceptor.preSend()` → `destination.substring("/topic/events/".length())` vs `sessionAttributes.get("tenantId")` → mismatch throws `IllegalArgumentException("Forbidden")`
> 4. `KafkaEventConsumer.consume(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic)` — **single method** handles all 4 topics via `@Header` injection
> 5. `objectMapper.readTree(message)` → extract `tenantId` → if blank, **silently drop**
> 6. `RealtimeEvent.builder().topic(topic).payload(node).receivedAt(Instant.now()).build()`
> 7. `messagingTemplate.convertAndSend("/topic/events/" + tenantId, event)`
> 8. Frontend `SessionStateService.handleEvent()` switches on `event.topic`

### "Why not HTTP polling?"

> For 1000 concurrent agents polling every second, that's 1000 req/s just for status updates. WebSocket push sends data only when something changes, reducing server load by 99%+ and providing sub-200ms update latency.

---

## 📞 Twilio / Telephony Questions

### "How does a real phone call flow through the system?"

> **Exact method chain:**
> 1. Twilio → `POST /twilio/inbound?CallSid=CA...` → `TelephonyController.handleInbound()` → `TelephonyService.handleInboundCall()`
> 2. Idempotency: `repository.findByTwilioCallSid(callSid)` — if exists, return existing `internalCallId`
> 3. `callServiceClient.createInternalCall(tenantId, from)` [REST to call-service]
> 4. `saveNewSession()` → `repository.save(TelephonyCallSession{status="ringing"})` [PG INSERT]
> 5. **Controller** returns TwiML: `<Say>Please wait</Say><Redirect>/bridge?callSid=CA...</Redirect>`
> 6. Routing assigns → Kafka `routing-events ASSIGNED` → `TelephonyService.handleAssignment()` → `session.setAssignedAgentId(agentId)` [PG UPDATE]
> 7. Twilio polls `GET /bridge` → `TelephonyService.getBridgeTwiml()` → `<Dial answerOnBridge="true"><Client>AG_001</Client></Dial>`
> 8. Twilio status `in-progress` → `TelephonyService.handleStatusCallback()` → `callServiceClient.startCall()` [REST]
> 9. Twilio status `completed` → `callServiceClient.completeCall()` [REST] → call-service cascade
>
> **Token generation:** `TelephonyService.generateToken(agentId)` — two `ConcurrentHashMap`s (`tokenCache` + `tokenExpiry`), 5-second TTL. `identity = agentId` — this is what `<Client>AG_001</Client>` targets.

### "How does the agent hear the call?"

> The agent's browser requests a Twilio Access Token with a VoiceGrant from `/twilio/token`. The token's `identity` is the agent ID. When Twilio processes `<Client>AG_001</Client>`, it routes audio to the browser registered with that identity via WebRTC.

---

## 🗄️ Database / Redis / Kafka Questions

### "Why both Redis and PostgreSQL?"

> **Redis:** Sub-millisecond reads for routing decisions. Agent availability must be queried in real-time — you can't wait for a PostgreSQL query across thousands of agents.
> 
> **PostgreSQL:** Durability and strong consistency. If Redis crashes, PostgreSQL has the last known state. Audit trails, call records, and user accounts need ACID guarantees.
> 
> **Dual-write:** Agent state changes go to both. Redis is the "speed layer," PostgreSQL is the "truth layer."

### "What if Redis goes down?"

> Routing breaks immediately — no agent can be matched. But PostgreSQL still has all the durable data. When Redis comes back, agents re-login and their state is rebuilt. No permanent data loss.

### "Why Kafka instead of direct REST calls?"

> 1. **Decoupling** — call-service doesn't know about routing-service. It just publishes events
> 2. **Durability** — messages survive service restarts
> 3. **Fan-out** — one event can be consumed by 6+ services (websocket, analytics, audit, etc.)
> 4. **Replay** — if a consumer crashes, it can re-read from its last committed offset
> 5. **At-least-once delivery** — that's why we need the idempotency cache

### "How do you handle Kafka failures?"

> `shared-common/KafkaConfig.java` configures a `DefaultErrorHandler` with:
> - Exponential backoff (1s initial, 2x multiplier, 10s max)
> - Dead Letter Queue: failed messages go to `{topic}.DLQ`
> - Consumers throw `RuntimeException` on failures to trigger the DLQ mechanism
>
> **Per-consumer behavior on failure:**
> - `KafkaAuditConsumer`: `@Transactional` — INSERT fails → rollback → offset not committed → automatic retry
> - `KafkaEventConsumer` (websocket-gateway): throws `RuntimeException("Failed to process Kafka message, throwing to trigger DLQ")`
> - `TelephonyService.handleAssignment()`: throws `RuntimeException("Telephony session not found")` if session missing → Kafka retry
> - `AnalyticsEventConsumer`: `if (tenantId == null) return` — **silent drop**, no retry

---

## 🧩 Design Pattern Questions

### "What design patterns did you use?"

| Pattern | Where | Why |
|---|---|---|
| **Event-Driven Architecture** | All inter-service communication | Decoupling, fan-out, durability |
| **CQRS-lite** | Analytics service reads from Kafka, serves via REST | Separate write (Kafka) and read (PostgreSQL) paths |
| **Idempotency Cache** | Routing service | Handles Kafka's at-least-once delivery |
| **Distributed Lock** | Routing service | Prevents duplicate call assignment |
| **State Machine** | Agent state, call status | Enforces valid transitions, prevents illegal states |
| **Heartbeat / Lease** | Agent disconnect detection | Detects crashed browsers without polling |
| **Dual Write** | Agent state (Redis + PG) | Speed layer + truth layer |
| **API Gateway** | Single entry point | Centralized auth, RBAC, rate limiting potential |
| **Dead Letter Queue** | Kafka error handling | Prevents poison messages from blocking consumers |
| **Fibonacci Backoff** | Retry processor | Gradually increasing retry intervals to reduce load |

### "What would you change in production?"

| Current | Production Improvement |
|---|---|
| In-memory SimpleBroker for WebSocket | Redis-backed broker for horizontal scaling |
| Shared JWT secret | RSA key pair (asymmetric) or OAuth2/OIDC |
| Single PostgreSQL instance | Read replicas, connection pooling (PgBouncer) |
| Single Redis instance | Redis Cluster or Sentinel for HA |
| Manual agent provisioning | Self-service registration with email verification |
| Hardcoded Twilio credentials | AWS Secrets Manager / Vault |
| No rate limiting | Rate limiting in API Gateway |
| No circuit breaker | Resilience4j for service-to-service calls |
| No distributed tracing | OpenTelemetry + Jaeger |
| Rolling average for wait time | Time-series database (TimescaleDB) for proper analytics |

---

## 🐛 Debugging Story (The Killer Interview Answer)

### "Tell me about a difficult bug you debugged."

> **Setup:** During testing, I noticed that after hitting F5 to refresh, my agent status kept flickering between OFFLINE and BUSY indefinitely, even though I wasn't on any call.
>
> **Investigation:** I pulled logs from three services simultaneously — agent-state-service, call-service, and routing-service — and correlated them by timestamp. I found a repeating cycle every ~10 seconds:
> 1. `AgentStateService.detectDisconnects()`: AGENT_DISCONNECTED published
> 2. `CallService.handleAgentDisconnect()`: "Requeuing call for disconnected agent" (`newCall: false`)
> 3. `RoutingEngine.assignAgent()`: cache key `routing:assignment:call:{id}` hit → returned `AG_001` without checking if online
> 4. `AgentStateService.handleRoutingEvent()`: accepted ASSIGNED → forced OFFLINE → BUSY
> 5. Back to step 1 — infinite loop
>
> **Root cause:** `RoutingEngine` trusted `routing:assignment:call:{callId}` without validating the cached agent's live Redis status. This is a specific failure of the "cache-as-truth" anti-pattern in distributed systems.
>
> **Fix — two exact code locations:**
> 1. **`RoutingEngine.assignAgent()`:** On cache hit, read `tenant:{id}:agent:{agentId}:state` hash field `status`. If `"OFFLINE"` → `DEL routing:assignment:call:{callId}` → re-run Lua script
> 2. **`AgentStateService.handleRoutingEvent()`:** Added: `if (agent.getStatus() == OFFLINE) { log.warn("Ignoring routing event... Breaking the ping-pong loop."); return; }`
>
> **Lesson:** In distributed systems, every cache must be validated against the live state before being trusted. "At-least-once" delivery combined with a stale cache creates infinite retry loops. Defense in depth matters — fix it at both the producer (routing) and consumer (agent-state) side.

---

## 📊 Numbers to Remember

| Metric | Value |
|---|---|
| Services | 9 microservices |
| Kafka topics | 5 (+ telephony-events) |
| PostgreSQL databases | 7 (one per service, analytics shares) |
| Redis key families | ~12 distinct patterns |
| Heartbeat interval | 15 seconds (browser → server) |
| Heartbeat timeout | 30 seconds (TTL on Redis key) |
| Disconnect scan | Every 10 seconds (`@Scheduled`) |
| JWT expiry | 1 hour |
| Idempotency cache TTL | 1 hour |
| Distributed lock TTL | 10 seconds |
| Retry processor interval | Every 5 seconds |
| Max retries before abandon | 10 retries (~114 seconds total) |
| Token cache (Twilio) | 5 seconds (in-memory) |
| Fibonacci backoff max | 30 seconds |
| WebSocket reconnect | Handled by SockJS (automatic) |

---

## 🗂️ File Quick Reference

| What | File |
|---|---|
| JWT validation | `api-gateway/.../filter/JwtAuthenticationFilter.java` |
| JWT generation | `shared-common/.../util/JwtUtil.java` |
| Agent state machine | `agent-state-service/.../service/AgentStateService.java` |
| Routing Lua script | `routing-service/.../engine/RoutingEngine.java` |
| Routing Kafka consumer | `routing-service/.../kafka/KafkaMessaging.java` |
| Retry processor | `routing-service/.../service/RetryProcessor.java` |
| Queue manager | `routing-service/.../service/QueueManager.java` |
| Call lifecycle | `call-service/.../service/CallService.java` |
| Agent disconnect recovery | `call-service/.../kafka/AgentEventConsumer.java` |
| Twilio bridge logic | `telephony-service/.../service/TelephonyService.java` |
| Twilio webhook controller | `telephony-service/.../controller/TelephonyController.java` |
| WebSocket config | `websocket-gateway/.../config/WebSocketConfig.java` |
| WebSocket auth interceptor | `websocket-gateway/.../config/AuthChannelInterceptor.java` |
| Kafka → browser push | `websocket-gateway/.../kafka/KafkaEventConsumer.java` |
| Analytics Kafka consumer | `analytics-service/.../kafka/AnalyticsEventConsumer.java` |
| Analytics counter service | `analytics-service/.../service/AnalyticsService.java` |
| Audit Kafka consumer | `audit-service/.../kafka/KafkaAuditConsumer.java` |
| Frontend state machine | `minigenesys-dashboard/.../services/session-state.service.ts` |
| Kafka error handling | `shared-common/.../config/KafkaConfig.java` |

---

## ⚡ Critical Method Names (Say These in Interviews)

| Service | Entry Point | Key Method | What It Does |
|---|---|---|---|
| agent-state-service | `POST /agents/{id}/login` | `AgentStateService.changeState()` | Dual-write: PG + Redis, publishes to `agent-events` |
| agent-state-service | `POST /agents/{id}/heartbeat` | `AgentStateService.handleHeartbeat()` | `SETEX heartbeatKey 30000ms` |
| agent-state-service | `@Scheduled` | `AgentStateService.detectDisconnects()` | PG query → OFFLINE transition → `AGENT_DISCONNECTED` |
| agent-state-service | Kafka: `routing-events` | `AgentStateService.handleRoutingEvent()` | Sets BUSY; **returns early if OFFLINE** (ping-pong fix) |
| agent-state-service | Kafka: `call-lifecycle-events` | `AgentStateService.handleCallCompletion()` | Sets AVAILABLE, re-adds to ZADD skill sets |
| call-service | `POST /calls` | `CallService.createCall()` | INSERT call QUEUED + publish `call-events` |
| call-service | Kafka: `routing-events` | `CallService.handleRoutingEvent()` | Updates call status: ROUTED / NO_AGENT / ABANDONED |
| call-service | Kafka: `agent-events` | `CallService.handleAgentDisconnect()` | Requeues call with `newCall: false` |
| routing-service | Kafka: `call-events` | `KafkaMessaging.consumeCallEvent()` → `routingEngine.assignAgent()` | Lock → Lua → publish |
| telephony-service | `POST /twilio/inbound` | `TelephonyController.handleInbound()` → `TelephonyService.handleInboundCall()` | Idempotency check → REST createCall → PG session INSERT |
| telephony-service | `GET /twilio/bridge` | `TelephonyService.getBridgeTwiml()` | Returns `<Dial><Client>` or polls |
| telephony-service | Kafka: `routing-events` | `TelephonyService.handleAssignment()` | Sets `assignedAgentId` in session [PG UPDATE] |
| websocket-gateway | STOMP CONNECT | `AuthChannelInterceptor.preSend()` | JWT validation + `sessionAttributes.put("tenantId")` |
| websocket-gateway | Kafka: 4 topics | `KafkaEventConsumer.consume()` | `readTree()` → extract tenantId → `convertAndSend()` |
| analytics-service | Kafka: 4 topics | `AnalyticsEventConsumer.consume()` | switch(topic) → dispatch to specific handler → `updateMetric()` |
| audit-service | Kafka: 5 topics | `KafkaAuditConsumer.consume()` [@Transactional] | Parse → extract metadata → `auditRepository.save()` |

---

## 🔄 Kafka Consumer Group Map

| Topic | Consumers (by group) |
|---|---|
| `call-events` | routing-service-group, analytics-service-group, audit-service-group, websocket-gateway-group |
| `routing-events` | agent-state-service-group, call-service-group, telephony-service-group, analytics-service-group, audit-service-group, websocket-gateway-group |
| `agent-events` | analytics-service-group, audit-service-group, websocket-gateway-group |
| `call-lifecycle-events` | agent-state-service-group, analytics-service-group, audit-service-group, websocket-gateway-group |
| `user-events` | audit-service-group only |
| `telephony-events` | **nobody** (published for future use) |
