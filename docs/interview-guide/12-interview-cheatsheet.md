# Interview Cheatsheet — Mini Genesys

Quick-fire answers to every question an interviewer will ask about this project. Organized by topic.

---

## 🎯 The Elevator Pitch (30 seconds)

> "I built a multi-tenant cloud contact center platform with 9 Spring Boot microservices. When a customer call comes in, it's persisted and published to Kafka. A routing service consumes the event and runs a Redis Lua script to atomically find the best available agent by intersecting skill-based sorted sets with LRU scheduling. The agent's browser gets a real-time WebSocket push within 200ms. If no agent is available, the call enters a retry queue with Fibonacci backoff. I also designed heartbeat-based disconnect detection and debugged a production-grade distributed race condition where browser refreshes caused infinite call re-assignment loops."

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

> 1. **Consume** — Routing service reads a `call-events` Kafka message
> 2. **Lock** — Acquires a distributed Redis lock for the call ID (prevents duplicate routing)
> 3. **Idempotency check** — Checks if this call was already assigned (handles Kafka duplicates)
> 4. **Lua script** — Atomically: ZINTERSTORE across skill sorted sets → ZRANGE to pick the lowest-scored (LRU) agent → ZREM from all sets → HSET state to BUSY
> 5. **Persist** — Saves assignment to PostgreSQL
> 6. **Cache** — Stores assignment in Redis (1-hour TTL) for idempotency
> 7. **Publish** — Sends `ASSIGNED` event to `routing-events` Kafka topic

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

> **Heartbeat mechanism:**
> 1. Browser sends `POST /agents/{id}/heartbeat` every 15 seconds
> 2. Server stores the timestamp in Redis with a 30-second TTL
> 3. A `@Scheduled` job (`detectDisconnects()`) runs every 10 seconds
> 4. It queries PostgreSQL for agents whose `lastHeartbeatAt` is older than 30 seconds
> 5. If found, the agent is marked OFFLINE and an `AGENT_DISCONNECTED` event is published to Kafka
> 6. The call-service consumes this event and requeues any active calls for that agent

### "Tell me about the bug you fixed."

> **The Ghost Call Re-assignment Loop:**
> 
> An F5 refresh killed the heartbeat for ~30 seconds. This triggered:
> 1. Agent marked OFFLINE by disconnect detector
> 2. Call-service requeued the active call to "save" the customer
> 3. Routing-service checked its idempotency cache, found the same agent ID, and blindly re-assigned the call
> 4. Agent-state-service received the ASSIGNED event and changed the OFFLINE agent back to BUSY
> 5. Steps 2-4 repeated in an infinite loop every ~10 seconds
> 
> **Root cause:** The idempotency cache didn't verify if the cached agent was still online.
> 
> **Fix (two layers):**
> - **Routing engine:** Before trusting the cache, verify the agent's Redis state. If OFFLINE, delete the cache and re-route
> - **Agent state service:** Reject ASSIGNED events if the agent is currently OFFLINE (defense in depth)

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

> **STOMP over SockJS:**
> 1. Browser opens a WebSocket connection to `/ws` with JWT in the CONNECT frame
> 2. `AuthChannelInterceptor` validates the JWT, stores tenantId in session
> 3. Browser subscribes to `/topic/events/{tenantId}`
> 4. Interceptor verifies the subscription tenant matches the JWT tenant
> 5. `KafkaEventConsumer` consumes from 4 Kafka topics
> 6. Wraps each event in a `RealtimeEvent` envelope with topic + timestamp
> 7. Pushes to `/topic/events/{tenantId}` via `SimpMessagingTemplate`
> 8. Frontend's `SessionStateService.handleEvent()` routes by topic to update UI

### "Why not HTTP polling?"

> For 1000 concurrent agents polling every second, that's 1000 req/s just for status updates. WebSocket push sends data only when something changes, reducing server load by 99%+ and providing sub-200ms update latency.

---

## 📞 Twilio / Telephony Questions

### "How does a real phone call flow through the system?"

> 1. Customer dials → Twilio POSTs to `/twilio/inbound` with CallSid
> 2. Telephony service creates an internal call via REST to call-service
> 3. Saves a `TelephonyCallSession` mapping CallSid ↔ internalCallId
> 4. Returns TwiML: "Please wait" + redirect to `/bridge`
> 5. Routing-service finds an agent, publishes ASSIGNED event
> 6. Telephony-service consumes the event, stores the assignedAgentId
> 7. Twilio polls `/bridge`, gets TwiML: `<Dial><Client>AG_001</Client></Dial>`
> 8. Twilio connects the caller's audio to the agent's WebRTC browser session
> 9. Twilio sends status callbacks (in-progress, completed) which trigger call lifecycle transitions

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
> 1. `agent-state-service`: AGENT_DISCONNECTED (heartbeat timeout)
> 2. `call-service`: "Requeuing call for disconnected agent"
> 3. `routing-service`: "Assignment cache hit — returning AG_001"
> 4. `agent-state-service`: "Agent AG_001 assigned to call" → BUSY
> 5. Back to step 1
>
> **Root Cause:** A race condition between three services. The routing service's idempotency cache remembered the old assignment and blindly returned it without checking if the agent was still online. The agent-state-service accepted the ASSIGNED event even though the agent was OFFLINE, which forced them back to BUSY.
>
> **Fix:** Two defensive layers:
> 1. **Routing engine** — Before trusting the idempotency cache, verify the agent's current Redis state. If offline, invalidate the cache and re-route
> 2. **Agent state service** — Reject any ASSIGNED event when the agent is OFFLINE (defense in depth)
>
> **Lesson:** In distributed systems, every cache must be validated against the source of truth before being trusted. "At-least-once" delivery combined with caching can create infinite loops if the cache doesn't account for state changes that happened after the cache was written.

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
| Retry processor | `routing-service/.../service/RetryProcessor.java` |
| Queue manager | `routing-service/.../service/QueueManager.java` |
| Call lifecycle | `call-service/.../service/CallService.java` |
| Agent disconnect recovery | `call-service/.../kafka/AgentEventConsumer.java` |
| Twilio bridge logic | `telephony-service/.../service/TelephonyService.java` |
| WebSocket auth | `websocket-gateway/.../config/AuthChannelInterceptor.java` |
| Kafka → browser push | `websocket-gateway/.../kafka/KafkaEventConsumer.java` |
| Frontend state machine | `minigenesys-dashboard/.../services/session-state.service.ts` |
| Kafka error handling | `shared-common/.../config/KafkaConfig.java` |
