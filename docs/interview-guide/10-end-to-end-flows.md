# Mini Genesys — End-to-End Flows

Every flow below is traced through the actual codebase with real class names, method calls, Kafka topics, and Redis keys.

---

## 1. Supervisor Login Flow

**Trigger:** Supervisor enters username + password on the Angular login page.

```
Browser                    API Gateway (8080)           User Service (8081)
  │                              │                            │
  │  POST /api/v1/auth/login     │                            │
  │  { username, password }      │                            │
  │─────────────────────────────►│                            │
  │                              │  Forward to user-service   │
  │                              │───────────────────────────►│
  │                              │                            │  BCrypt.matches(password, hash)
  │                              │                            │  Generate JWT with:
  │                              │                            │    - userId, tenantId, role
  │                              │                            │    - agentId (if role=AGENT)
  │                              │  ◄── { token, user info } ─┤
  │  ◄── { token, user info } ──│                            │
  │                              │                            │
  │  Store token in localStorage │                            │
  │  Navigate to /dashboard      │                            │
```

**Granular Code Tracing:**
- **API Endpoint:** `POST /api/v1/auth/login` (Forwarded by API Gateway to `user-service`).
  - *Purpose:* Authenticate supervisor, locate tenant context, and return JWT for secure REST and WebSocket operations.
- **Method Called:** `UserService.authenticate(request)`
  - *Purpose:* Entrypoint for user validation.
- **PostgreSQL Read (Performed by User Service):** `SELECT * FROM users WHERE username = ?` via `UserRepository.findByUsername()`
  - *Purpose:* Fetches stored credentials (hashed password, salt, role, and tenantId).
- **Security Check (Performed by User Service):** `BCrypt.matches(password, storedHash)`
  - *Purpose:* Cryptographically matches the client-supplied password against the hashed value.
- **State/Token Generation (Performed by User Service):** JWT signing (stateless session generation).
  - *Purpose:* Constructs JWT claims containing `userId`, `tenantId`, and `role: SUPERVISOR` so subsequent components can skip database queries.
- **Frontend Action:** Stores token in `localStorage`, updates routing guards, and calls `ApiService` to fetch initial UI frames.

**Supervisor vs Agent difference:**
- Supervisors see analytics dashboards and agent monitoring
- Agents see their personal agent panel with call controls

---

## 2. Agent Login / Start Shift Flow

**Trigger:** Agent logs in, then clicks "Start Shift" on the dashboard.

```
Browser                  API Gateway         Agent State Service        Redis              Kafka
  │                          │                       │                    │                  │
  │ POST /login              │                       │                    │                  │
  │─────────────────────────►│──► User Service ──────┤                    │                  │
  │ ◄── JWT token ───────────│                       │                    │                  │
  │                          │                       │                    │                  │
  │ POST /agents/AG_001/login│                       │                    │                  │
  │─────────────────────────►│──────────────────────►│                    │                  │
  │                          │                       │ changeState(       │                  │
  │                          │                       │   OFFLINE→AVAILABLE│                  │
  │                          │                       │ )                  │                  │
  │                          │                       │                    │                  │
  │                          │                       │ HSET agent state   │                  │
  │                          │                       │───────────────────►│                  │
  │                          │                       │ ZADD skill sets    │                  │
  │                          │                       │───────────────────►│                  │
  │                          │                       │                    │                  │
  │                          │                       │ Publish AGENT_AVAILABLE               │
  │                          │                       │───────────────────────────────────────►│
  │                          │                       │                    │                  │
  │ ◄── { status: AVAILABLE }│◄──────────────────────│                    │                  │
  │                          │                       │                    │                  │
  │ Start heartbeat timer    │                       │                    │                  │
  │ (every 15 seconds)       │                       │                    │                  │
```

**Granular Code Tracing:**
- **API Endpoint:** `POST /api/v1/agents/{agentId}/login` (Targeting `agent-state-service`).
  - *Purpose:* Signals system to register this agent as ready to take inbound calls.
- **Method Called:** `AgentStateService.changeState(agentId, AgentStatus.OFFLINE, AgentStatus.AVAILABLE)`
  - *Purpose:* Core business logic processor. Checks constraints to ensure valid status state-machine pathing.
- **PostgreSQL Write (Performed by Agent State Service):** `UPDATE agents SET status = 'AVAILABLE' WHERE id = 'AG_001'` (via `AgentRepository.save()`)
  - *Purpose:* Persists agent state durably for audits, compliance reports, and supervisor lookups.
- **Redis Write (State Cache - Performed by Agent State Service):** `HSET tenant:tenant1:agent:AG_001:state status "AVAILABLE"`
  - *Purpose:* Fast in-memory state store for routing engine to query.
- **Redis Write (Skill Sets - Performed by Agent State Service):** `ZADD tenant:tenant1:skill:sales:available {epochMs} AG_001`
  - *Purpose:* Registers agent under each of their skill queues. The score (`epochMs` of shift start) implements the Least-Recently-Used (LRU) routing policy.
- **Redis Write (Heartbeat - Performed by Agent State Service via subsequent Heartbeat API):** `SET tenant:tenant1:agent:AG_001:heartbeat {now} EX 30` (or `PX 30000`)
  - *Purpose:* Sets a TTL-bound token to identify that the agent is actively connected. The login response triggers the client to start sending periodic `/heartbeat` requests, which perform this write.
- **Kafka Publish (Performed by Agent State Service):** Publishes `AGENT_AVAILABLE` payload to `agent-events` topic.
  - *Purpose:* Triggers downstream actions:
    - **Dashboard Updates:** Consumed by `websocket-gateway` to push the agent's new status to the supervisor's live monitoring panel.
    - **Analytics Counters:** Consumed by `analytics-service` to decrement the offline count and increment the available agents count in PostgreSQL counters.
- **Frontend Action:** Starts browser-based interval (`SessionStateService.startHeartbeat()`) sending `POST /agents/AG_001/heartbeat` every 15s.

---

## 3. Agent Ready Flow

**Trigger:** Agent clicks "Set Ready" after completing a call (transitions from `BUSY → AVAILABLE`).

This is identical to the login flow but with a different state transition:
- **API Endpoint:** `POST /api/v1/agents/{agentId}/available` (Targeting `agent-state-service`).
  - *Purpose:* Transition agent state back to routable status.
- **Method Called:** `AgentStateService.changeState(agentId, AgentStatus.BUSY, AgentStatus.AVAILABLE)`
  - *Purpose:* Validates state transition.
- **PostgreSQL Write (Performed by Agent State Service):** `UPDATE agents SET status = 'AVAILABLE', active_call_id = NULL WHERE id = 'AG_001'`
  - *Purpose:* Clears call reference and sets status to AVAILABLE.
- **Redis Write (State Cache - Performed by Agent State Service):** `HSET tenant:tenant1:agent:AG_001:state status "AVAILABLE"`
- **Redis Write (Skill Sets - Performed by Agent State Service):** `ZADD tenant:tenant1:skill:sales:available {epochMs} AG_001` (makes agent routable again)
- **Kafka Publish (Performed by Agent State Service):** Sends `AGENT_AVAILABLE` event to Kafka.
  - *Purpose:* Updates supervisor console via `websocket-gateway` and changes status counts in `analytics-service`.

**Important:** This transition normally happens **automatically** when the `call-lifecycle-events` topic delivers a `CALL_COMPLETED` event. The `AgentStateService.handleCallCompletion()` method handles this.

---

## 4. Call Creation Flow

Trigger: Agent clicks "Simulate Call" on the dashboard, OR a real Twilio inbound call arrives.

---

### A. Simulated Call Creation Path (Dashboard)

```
Browser                  API Gateway         Call Service              Kafka
  │                          │                    │                      │
  │ POST /api/v1/calls       │                    │                      │
  │ { requiredSkills: [sales]│                    │                      │
  │   priority: 1 }          │                    │                      │
  │─────────────────────────►│───────────────────►│                      │
  │                          │                    │ INSERT into calls     │
  │                          │                    │ status = QUEUED       │
  │                          │                    │                      │
  │                          │                    │ Publish call-events   │
  │                          │                    │─────────────────────►│
  │                          │                    │                      │
  │ ◄── { callId, QUEUED } ──│◄───────────────────│                      │
```

**Granular Code Tracing:**
- **API Endpoint:** `POST /api/v1/calls` (Targeting `call-service`).
  - *Purpose:* Generates a simulated inbound call for routing testing.
- **Method Called (Call Service):** `CallService.createCall(tenantId, CreateCallRequest)`
  - *Purpose:* Validates skills exist, generates a new call UUID, sets initial status to `QUEUED`, and triggers Kafka event publish.
- **PostgreSQL Write (Call Service):** `INSERT INTO calls (id, tenant_id, status, required_skills, priority) VALUES (?, ?, 'QUEUED', ?, ?)`
  - *Purpose:* Creates the master source-of-truth call record in the `minigenesys_call` database.
- **Kafka Publish (Performed by Call Service):** Sends a payload to the `call-events` topic containing `{callId, tenantId, requiredSkills, priority, newCall: true}`.
  - *Purpose:* Launches async matching and updates downstream telemetry:
    - **Trigger Routing:** It triggers `routing-service`'s `consumeCallEvent()` to run the Lua script matching logic and assign an available agent immediately.
    - **Trigger Dashboards:** It triggers `websocket-gateway` to broadcast the call to the supervisor dashboard.
    - **Trigger Analytics:** It triggers `analytics-service` to increment `totalCalls` and `queuedCalls` in PostgreSQL counters.

---

### B. Real Twilio Inbound Call Creation Path

```
Twilio                   Telephony Service       Call Service            Kafka
  │                           │                       │                    │
  │ POST /api/v1/telephony/   │                       │                    │
  │      twilio/inbound       │                       │                    │
  │ { CallSid, From, To }     │                       │                    │
  │──────────────────────────►│                       │                    │
  │                           │ REST: createInternalCall                   │
  │                           │──────────────────────►│                    │
  │                           │                       │ INSERT call        │
  │                           │                       │ Publish call-events│
  │                           │                       │───────────────────►│
  │                           │ ◄── callId ───────────│                    │
  │                           │                       │                    │
  │ ◄── TwiML: "Please wait"  │                       │                    │
  │     + Redirect to /bridge │                       │                    │
```

**Granular Code Tracing:**
- **API Endpoint (Real Call):** `POST /api/v1/telephony/twilio/inbound` (Targeting `telephony-service`).
  - *Purpose:* Receives Twilio's incoming call webhook parameters.
- **Method Called (Telephony Service):** `TelephonyService.handleInboundCall(callSid, from, to, tenantId)`
  - *Purpose:* Handles internal call creation and session persistence.
- **REST Call (Telephony Service → Call Service):** `POST /api/v1/calls` (via `CallServiceClient.createInternalCall()`).
  - *Purpose:* Triggers call creation on Call Service, returning the internal call UUID.
- **PostgreSQL Write (Call Service):** `INSERT INTO calls (id, tenant_id, status, required_skills, priority) VALUES (?, ?, 'QUEUED', ?, ?)`
  - *Purpose:* Creates the master source-of-truth call record in the `minigenesys_call` database.
- **PostgreSQL Write (Telephony Service):** `INSERT INTO telephony_call_sessions (twilio_call_sid, internal_call_id, from_number, to_number, tenant_id, status) VALUES (?, ?, ?, ?, ?, 'ringing')`
  - *Purpose:* Maps Twilio's external `CallSid` to the internal call UUID in the `minigenesys_telephony` database.
- **Kafka Publish (Performed by Call Service):** Sends a payload to the `call-events` topic containing `{callId, tenantId, requiredSkills, priority, newCall: true}`.
  - *Purpose:* Launches async matching and updates downstream telemetry:
    - **Trigger Routing:** It triggers `routing-service`'s `consumeCallEvent()` to run the Lua script matching logic and assign an available agent immediately.
    - **Trigger Dashboards:** It triggers `websocket-gateway` to broadcast the call to the supervisor dashboard.
    - **Trigger Analytics:** It triggers `analytics-service` to increment `totalCalls` and `queuedCalls` in PostgreSQL counters.

---

## 5. Agent Assignment Flow

**Trigger:** Routing Service consumes a `call-events` message from Kafka.

```
Kafka              Routing Service                    Redis                   Kafka (out)
  │                     │                               │                        │
  │ call-events msg     │                               │                        │
  │────────────────────►│                               │                        │
  │                     │ KafkaMessaging.consumeCallEvent()                       │
  │                     │                               │                        │
  │                     │ 1. Acquire distributed lock   │                        │
  │                     │    SET routing:lock:call:{id}  │                        │
  │                     │──────────────────────────────►│                        │
  │                     │                               │                        │
  │                     │ 2. Check idempotency cache    │                        │
  │                     │    GET routing:assignment:call:{id}                     │
  │                     │──────────────────────────────►│                        │
  │                     │    (null = first time)        │                        │
  │                     │                               │                        │
  │                     │ 3. Execute Lua script         │                        │
  │                     │    ZINTERSTORE across skill   │                        │
  │                     │    sets → find best agent     │                        │
  │                     │    ZREM agent from skill sets │                        │
  │                     │    HSET agent state → BUSY    │                        │
  │                     │──────────────────────────────►│                        │
  │                     │    returns: AG_001            │                        │
  │                     │                               │                        │
  │                     │ 4. Save assignment to PG      │                        │
  │                     │ 5. Cache in Redis (1hr TTL)   │                        │
  │                     │    SET routing:assignment:call:{id} = AG_001           │
  │                     │──────────────────────────────►│                        │
  │                     │                               │                        │
  │                     │ 6. Publish ASSIGNED           │                        │
  │                     │───────────────────────────────────────────────────────►│
  │                     │                               │                   routing-events
  │                     │ 7. Release lock               │                        │
  │                     │    DEL routing:lock:call:{id} │                        │
  │                     │──────────────────────────────►│                        │
```

**Granular Code Tracing:**
- **Kafka Consume:** `RoutingEventConsumer` receives the `call-events` message.
- **Method Called:** `RoutingService.processCallRouting(CallEvent)` -> `RoutingEngine.assignAgent(CallRequest)`
  - *Purpose:* Decides whether to assign the call or buffer it based on agent availability.
- **Redis Write (Lock Acquisition - Performed by Routing Service):** `SET routing:lock:call:{callId} "locked" NX PX 10000`
  - *Purpose:* Implements a distributed lock to prevent multiple instances of `routing-service` from routing the same call concurrently.
- **Redis Read (Idempotency Check - Performed by Routing Service):** `GET routing:assignment:call:{callId}`
  - *Purpose:* Verifies whether this call has already been assigned during a previous retry or redelivery.
- **Redis Write (Lua script - Performed by Routing Service):** Executes `SELECT_AGENT_LUA` atomically.
  - *Actions performed in Lua:*
    1. `ZINTERSTORE` — intersects all required skill sets `tenant:{tenantId}:skill:{skill}:available` to locate agents matching ALL skills.
    2. `ZRANGE ... 0 0` — picks the member with the lowest score (the Least-Recently-Used agent).
    3. `ZREM` — removes the selected agent from all skill availability sorted sets.
    4. `HSET` — transitions the agent's cached status to `BUSY` in `tenant:{tenantId}:agent:{agentId}:state`.
- **PostgreSQL Write (Performed by Routing Service):** `INSERT INTO assignments (id, call_id, agent_id, status, assigned_at) VALUES (?, ?, ?, 'ASSIGNED', ?)`
  - *Purpose:* Durably records the routing decision in the `minigenesys_routing` database.
- **Redis Write (Cache Assignment - Performed by Routing Service):** `SET routing:assignment:call:{callId} {agentId} EX 3600`
  - *Purpose:* Caches the routing mapping for 1 hour to handle future idempotency checks.
- **Redis Write (Release Lock - Performed by Routing Service):** `DEL routing:lock:call:{callId}`
  - *Purpose:* Unlocks the call resource.
- **Kafka Publish (Performed by Routing Service):** Sends a payload to the `routing-events` topic containing `{callId, agentId, tenantId, status: ASSIGNED}`.
  - *Purpose:* Triggers parallel state synchronization across 5 downstream services:
    - `agent-state-service` consumes event → marks agent `BUSY` in PG & Redis.
    - `call-service` consumes event → marks call `ROUTED` and stores `assignedAgentId` in PG.
    - `telephony-service` consumes event → maps agent ID to the Twilio session.
    - `websocket-gateway` consumes event → pushes live incoming call panel updates to the agent browser.
    - `analytics-service` consumes event → increments `routedCalls` and decrements `queuedCalls` in PG.

---

## 6. No-Agent Retry Flow

**Trigger:** The Lua script returns `null` — no available agent matches the required skills.

```
Routing Service              Redis (QueueManager)          RetryProcessor (scheduled)
  │                               │                              │
  │ Lua returns null              │                              │
  │                               │                              │
  │ Publish NO_AGENT event        │                              │
  │──► Kafka routing-events       │                              │
  │                               │                              │
  │ QueueManager.enqueue(call)    │                              │
  │──────────────────────────────►│                              │
  │   ZADD call:queue             │                              │
  │   SET call data + retry=0    │                              │
  │                               │                              │
  │                               │     Every 5 seconds:         │
  │                               │◄─────────────────────────────│ processQueuedCalls()
  │                               │                              │
  │                               │  Check backoff elapsed?      │
  │                               │  Load CallRequest from Redis │
  │                               │─────────────────────────────►│
  │                               │                              │ routingEngine.assignAgent()
  │                               │                              │
  │                               │                              │ If SUCCESS:
  │                               │  dequeue call                │   produce ASSIGNED event
  │                               │◄─────────────────────────────│
  │                               │                              │
  │                               │                              │ If FAIL (NO_AGENT):
  │                               │  increment retry count       │   log + wait for next cycle
  │                               │◄─────────────────────────────│
  │                               │                              │
  │                               │                              │ If retryCount >= 10:
  │                               │  dequeue call                │   produce ABANDONED event
  │                               │◄─────────────────────────────│
```

**Granular Code Tracing:**
- **Method Called (Enqueue):** `QueueManager.enqueue(CallRequest)`
  - *Purpose:* Places the unassigned call into the retry buffer.
- **Redis Write (Retry Queue - Performed by Routing Service):** `ZADD tenant:{tenantId}:call:queue {score} {callId}`
  - *Purpose:* Buffers the call in a priority-sorted set where the score represents next allowable processing epoch (incorporating Fibonacci backoff).
- **Redis Write (Call Payload - Performed by Routing Service):** `SET tenant:{tenantId}:call:{callId} {jsonPayload}`
  - *Purpose:* Caches call matching properties so retry workers do not have to perform slow relational SQL queries.
- **Redis Write (Active Tenants - Performed by Routing Service):** `SADD routing:active-tenants {tenantId}`
  - *Purpose:* Registers this tenant in a set of active queues so the background polling loops know to scan them.
- **Method Called (Scheduler):** `@Scheduled(fixedDelay = 5000) RetryProcessor.processQueuedCalls()`
  - *Purpose:* Periodic job that scans all active tenants in Redis and processes eligible calls.
- **Redis Read (Performed by Routing Service):** `ZRANGEBYSCORE tenant:{tenantId}:call:queue -inf {now}`
  - *Purpose:* Grabs all calls whose backoff delays have expired.
- **Method Called (Re-route):** `RoutingEngine.assignAgent()`
  - *Purpose:* Attempts to execute Lua script matching on the active call.
- **Branch: Failure (retryCount < 10):**
  - **Redis Write (Performed by Routing Service):** Updates the score in `tenant:{tenantId}:call:queue` with the next backoff timestamp and increments the retry counter.
  - **Kafka Publish (Performed by Routing Service):** Publishes `NO_AGENT` status on `routing-events` topic. Consumed by `call-service` to log routing attempts and updated in dashboards.
- **Branch: Success:**
  - **Redis Delete (Performed by Routing Service):** `ZREM tenant:{tenantId}:call:queue {callId}` and `DEL tenant:{tenantId}:call:{callId}`.
  - **Kafka Publish (Performed by Routing Service):** Publishes `ASSIGNED` on `routing-events` topic.
- **Branch: Abandoned (retryCount >= 10, elapsed ~114s):**
  - **Redis Delete (Performed by Routing Service):** Deletes call queue key and payload key.
  - **Kafka Publish (Performed by Routing Service):** Publishes `ABANDONED` on `routing-events` topic. Consumed by `call-service` to update database status to `ABANDONED` and trigger completion logic.

---

## 7. Call Start / Complete Flow

**Trigger:** Agent clicks "Start Call" and later "Complete Call" on the dashboard.

### Start Call
```
Browser              API Gateway         Call Service
  │                      │                    │
  │ POST /calls/{id}/start                    │
  │─────────────────────►│───────────────────►│
  │                      │                    │ Validate: status must be ROUTED
  │                      │                    │ UPDATE status = IN_PROGRESS
  │                      │                    │
  │ ◄── { IN_PROGRESS } ─│◄───────────────────│
```

### Complete Call
```
Browser         API Gateway      Call Service              Kafka              Agent State Service
  │                 │                 │                      │                       │
  │ POST /calls/{id}/complete         │                      │                       │
  │────────────────►│────────────────►│                      │                       │
  │                 │                 │ Validate: IN_PROGRESS│                       │
  │                 │                 │ UPDATE → COMPLETED   │                       │
  │                 │                 │                      │                       │
  │                 │                 │ Publish CALL_COMPLETED                       │
  │                 │                 │─────────────────────►│                       │
  │                 │                 │              call-lifecycle-events            │
  │                 │                 │                      │                       │
  │                 │                 │                      │ CallLifecycleConsumer  │
  │                 │                 │                      │──────────────────────►│
  │                 │                 │                      │                       │
  │                 │                 │                      │   handleCallCompletion()
  │                 │                 │                      │   BUSY → AVAILABLE    │
  │                 │                 │                      │   Clear activeCallId  │
  │                 │                 │                      │   ZADD skill sets     │
  │                 │                 │                      │   Publish AGENT_AVAILABLE
  │                 │                 │                      │◄──────────────────────│
  │                 │                 │                      │               agent-events
```

**Granular Code Tracing:**
- **API Endpoint (Start):** `POST /api/v1/calls/{callId}/start` (Targeting `call-service`).
  - *Purpose:* Transitions the call status to active conversation mode.
- **Method Called (Start):** `CallService.startCall(callId)`
  - *Purpose:* Validates status is currently `ROUTED` (preventing illegal starts).
- **PostgreSQL Write (Start):** `UPDATE calls SET status = 'IN_PROGRESS', started_at = ? WHERE id = ?`
  - *Purpose:* Persists the conversation start timestamp.
- **API Endpoint (Complete):** `POST /api/v1/calls/{callId}/complete` (Targeting `call-service`).
  - *Purpose:* Ends the call and clears references.
- **Method Called (Complete):** `CallService.completeCall(callId)`
  - *Purpose:* Validates status is currently `IN_PROGRESS`.
- **PostgreSQL Write (Complete):** `UPDATE calls SET status = 'COMPLETED', ended_at = ? WHERE id = ?`
  - *Purpose:* Records the conversation completion.
- **Kafka Publish (Lifecycle Event - Performed by Call Service):** Sends a payload to the `call-lifecycle-events` topic containing `{callId, agentId, tenantId, status: CALL_COMPLETED}`.
  - *Purpose:* Signals that the agent handling this call is now free:
    - Consumed by `agent-state-service`'s `CallLifecycleConsumer.handleCallCompletion(event)`.
    - **Method Called:** `AgentStateService.changeState(agentId, AgentStatus.BUSY, AgentStatus.AVAILABLE)`.
    - **PostgreSQL Write (Performed by Agent State Service):** `UPDATE agents SET status = 'AVAILABLE', active_call_id = NULL WHERE id = ?`.
    - **Redis Write (State Cache - Performed by Agent State Service):** `HSET tenant:{tenantId}:agent:{agentId}:state status "AVAILABLE"`
    - **Redis Write (Skill Sets - Performed by Agent State Service):** `ZADD tenant:{tenantId}:skill:{skill}:available {epochMs} {agentId}` (LRU re-insertion).
    - **Kafka Publish (Performed by Agent State Service):** Sends `AGENT_AVAILABLE` payload to `agent-events` topic. Consumed by analytics and websocket gateways.

**Call Status State Machine:**
```
QUEUED → ROUTED → IN_PROGRESS → COMPLETED
                                     ↓
                                  FAILED
```

- `QUEUED → ROUTED`: Set by call-service when it consumes an `ASSIGNED` routing-event
- `ROUTED → IN_PROGRESS`: Set by `CallService.startCall()` (REST call from browser or Twilio)
- `IN_PROGRESS → COMPLETED`: Set by `CallService.completeCall()`

---

## 8. Agent Call Rejection Flow

**Trigger:** Agent clicks "Reject" when a call is assigned to them.

```
Browser              API Gateway      Agent State Service         Call Service
  │                      │                    │                        │
  │ 1. POST /agents/{id}/logout               │                        │
  │─────────────────────►│───────────────────►│                        │
  │                      │                    │ Validate: oldStatus    │
  │                      │                    │ UPDATE status = OFFLINE│
  │                      │                    │ ZREM from skill queues │
  │                      │                    │                        │
  │ ◄── { status: OFFLINE } ──────────────────│                        │
  │                      │                    │                        │
  │ 2. POST /calls/{id}/status { "REJECTED" } │                        │
  │─────────────────────►│────────────────────┼───────────────────────►│
  │                      │                    │                        │ UPDATE status = QUEUED
  │                      │                    │                        │ Publish call-events
  │ ◄── { status: QUEUED } ───────────────────┼────────────────────────│
```

**Granular Code Tracing:**
- **API Endpoint 1:** `POST /api/v1/agents/{agentId}/logout` (Targeting `agent-state-service`).
  - *Purpose:* Log agent out immediately to avoid instant re-assignment to the same call.
- **Method Called (Logout):** `AgentStateService.changeState(agentId, AgentStatus.AVAILABLE, AgentStatus.OFFLINE)`
  - *Purpose:* Enforces agent shift completion.
- **PostgreSQL Write (Agent Logout - Performed by Agent State Service):** `UPDATE agents SET status = 'OFFLINE' WHERE id = ?`
  - *Purpose:* Durably marks the agent offline.
- **Redis Write (Agent Logout - Performed by Agent State Service):**
  - `DEL tenant:{tenantId}:agent:{agentId}:state` (clears state cache).
  - `ZREM tenant:{tenantId}:skill:{skill}:available {agentId}` (removes from all routing lists).
  - *(Note: The heartbeat key `tenant:{tenantId}:agent:{agentId}:heartbeat` is not deleted by the server on normal logout, but will expire naturally within 30s as the frontend stops sending updates.)*
- **API Endpoint 2:** `POST /api/v1/calls/{callId}/status` with body `{ "status": "REJECTED" }` (Targeting `call-service`).
  - *Purpose:* Returns the call to the queue.
- **Method Called (Rejection):** `CallService.rejectCall(callId)`
  - *Purpose:* Initiates requeuing and state release.
- **PostgreSQL Write (Call Requeue - Performed by Call Service):** `UPDATE calls SET status = 'QUEUED', assigned_agent_id = NULL WHERE id = ?`
  - *Purpose:* Resets call status back to queue.
- **Kafka Publish (Requeue Event - Performed by Call Service):** Sends a payload to the `call-events` topic containing `{callId, tenantId, requiredSkills, priority, newCall: false}`.
  - *Purpose:* Re-routes call without double-counting counters since `newCall: false` is supplied.
- **Kafka Publish (Release Agent - Performed by Call Service):** Sends a payload to the `call-lifecycle-events` topic containing `{callId, agentId, tenantId, status: CALL_COMPLETED}`.
  - *Purpose:* Guarantees the agent is released and state variables cleared.

**Known Bug (Fixed):** Originally, the dashboard fired both REST calls simultaneously. Because Kafka processing is extremely fast, the call was requeued and immediately re-assigned back to the same agent before the `OFFLINE` status could be written to Redis. The dashboard now chains these calls sequentially, ensuring the agent is fully removed from routing queues before the call is requeued.

---

## 9. Heartbeat Disconnect Flow

**Trigger:** The agent's browser crashes, they close the tab, or they hit F5 and the reload takes > 30 seconds.

```
Time 0s:    Agent's last heartbeat received
            Redis: SET heartbeat key, TTL=30s

Time 15s:   Agent should send next heartbeat
            (but browser crashed / is reloading)

Time 30s:   Redis TTL expires on heartbeat key

Time 30-40s: AgentStateService.detectDisconnects() runs (every 10s)
            │
            ├── Queries PostgreSQL: agents WHERE status IN (AVAILABLE, BUSY)
            │   AND lastHeartbeatAt < (now - 30000ms)
            │
            ├── Finds AG_001
            │
            ├── Sets agent OFFLINE in PostgreSQL
            ├── Removes agent from Redis skill sets
            ├── Deletes Redis state hash
            ├── Deletes Redis heartbeat key
            │
            └── Publishes AGENT_DISCONNECTED to Kafka
                    │
                    ▼
            Call Service (AgentEventConsumer)
                    │
                    ├── Searches: calls WHERE assignedAgentId = AG_001
                    │   AND status IN (ROUTED, IN_PROGRESS)
                    │
                    ├── For each active call:
                    │   ├── Sets call status = QUEUED
                    │   ├── Clears assignedAgentId
                    │   └── Publishes call-events (isNew: false) to Kafka
                    │
                    ▼
            Routing Service picks up the requeued call
            and tries to find a new agent
```

**Granular Code Tracing:**
- **Method Called (Sweeper):** `@Scheduled(fixedRate = 10000) AgentStateService.detectDisconnects()`
  - *Purpose:* Sweeps databases to clean up dead sessions.
- **PostgreSQL Read (Agent State DB - Performed by Agent State Service):** `SELECT * FROM agents WHERE status IN ('AVAILABLE', 'BUSY') AND last_heartbeat_at < ?`
  - *Purpose:* Fetches all agents whose heartbeats have timed out (> 30s delay).
- **PostgreSQL Write (Agent State DB - Performed by Agent State Service):** `UPDATE agents SET status = 'OFFLINE', last_heartbeat_at = ? WHERE id = ?`
  - *Purpose:* Marks the disconnected agent offline.
- **Redis Write (Cleanup - Performed by Agent State Service):**
  - `DEL tenant:{tenantId}:agent:{agentId}:state`
  - `ZREM tenant:{tenantId}:skill:{skill}:available {agentId}` (removes from matching pools)
  - `DEL tenant:{tenantId}:agent:{agentId}:heartbeat`
- **Kafka Publish (Disconnect - Performed by Agent State Service):** Sends `AGENT_DISCONNECTED` payload on the `agent-events` topic.
  - *Purpose:* Initiates orphaned call recovery.
- **Kafka Consume (Call Service):** `AgentEventConsumer.consumeAgentEvent()` receives the disconnect notification.
- **Method Called (Call Service):** `CallService.handleAgentDisconnect(AgentEvent)`
  - *Purpose:* Locates and rescues calls assigned to the disconnected agent.
- **PostgreSQL Read (Call DB - Performed by Call Service):** `SELECT * FROM calls WHERE assigned_agent_id = ? AND status IN ('ROUTED', 'IN_PROGRESS')`
  - *Purpose:* Locates active/ringing calls abandoned by the agent.
- **PostgreSQL Write (Call DB - Performed by Call Service):** `UPDATE calls SET status = 'QUEUED', assigned_agent_id = NULL WHERE id = ?`
  - *Purpose:* Returns orphaned calls back to a queue state.
- **Kafka Publish (Re-route - Performed by Call Service):** Sends `call-events` with `newCall: false` payload.
  - *Purpose:* Requeues calls to find a healthy agent without incrementing total statistics.

**Known Bug (Fixed):** Before our fix, the routing-service's idempotency cache still remembered the old agent assignment. When the requeued call arrived, the cache would blindly re-assign it to the disconnected agent, creating an infinite ping-pong loop. The fix validates agent status before trusting the cache.

---

## 10. Twilio Inbound Call Flow

**Trigger:** A real phone call hits the Twilio number, which POSTs to the telephony-service webhook.

```
Phone Call          Twilio Cloud        Telephony Service      Call Service       Routing Service
  │                     │                     │                     │                   │
  │ Customer dials      │                     │                     │                   │
  │────────────────────►│                     │                     │                   │
  │                     │ POST /twilio/inbound│                     │                   │
  │                     │ CallSid, From, To   │                     │                   │
  │                     │────────────────────►│                     │                   │
  │                     │                     │                     │                   │
  │                     │                     │ REST: POST /calls   │                   │
  │                     │                     │────────────────────►│                   │
  │                     │                     │                     │ Publish call-events│
  │                     │                     │                     │──────────────────►│
  │                     │                     │ ◄── callId ─────────│                   │
  │                     │                     │                     │                   │
  │                     │                     │ Save TelephonyCallSession               │
  │                     │                     │ (maps CallSid ↔ callId)                 │
  │                     │                     │                     │                   │
  │                     │ ◄── TwiML response ─│                     │                   │
  │                     │   "Please wait..."  │                     │                   │
  │                     │   Redirect → /bridge│                     │                   │
  │                     │                     │                     │                   │
  │ Customer hears      │                     │                     │   assignAgent()   │
  │ "Please wait..."    │                     │                     │                   │
  │                     │                     │                     │                   │
  │                     │                     │ Consumes routing-events (ASSIGNED)       │
  │                     │                     │ Updates session.assignedAgentId          │
  │                     │                     │                     │                   │
  │                     │ GET /twilio/bridge   │                     │                   │
  │                     │────────────────────►│                     │                   │
  │                     │                     │ Returns TwiML:      │                   │
  │                     │ ◄── <Dial><Client>  │  Bridge to AG_001   │                   │
  │                     │      AG_001         │                     │                   │
  │                     │                     │                     │                   │
  │ ◄── Voice connected ─────────────── Agent's browser (WebRTC)   │                   │
```

**Granular Code Tracing:**
- **API Endpoint 1:** `POST /api/v1/telephony/twilio/inbound` (Targeting `telephony-service`).
  - *Purpose:* External webhook endpoint called by Twilio when a voice trunk receives an inbound call.
- **Method Called (Telephony):** `TelephonyService.handleInboundCall()`
  - *Purpose:* Calls `CallServiceClient.createInternalCall()` to trigger call registration in `call-service`, then persists the session.
- **PostgreSQL Write (Telephony DB):** `INSERT INTO telephony_call_sessions (twilio_call_sid, internal_call_id, from_number, to_number, tenant_id, status) VALUES (?, ?, ?, ?, ?, 'ringing')`
  - *Purpose:* Maps external Twilio telephony SID to internal call identifier.
- **API Endpoint 2:** `GET /api/v1/telephony/twilio/bridge` with query parameter `callSid` (Targeting `telephony-service`).
  - *Purpose:* Callback URL hit by Twilio instructions to connect/bridge audio.
- **PostgreSQL Read (Telephony DB):** `SELECT * FROM telephony_call_sessions WHERE twilio_call_sid = ?`
  - *Purpose:* Checks if an agent has been assigned.
- **Branch: Agent unassigned:** Returns TwiML Redirect back to `/bridge` with a 3-second delay, implementing an audio waiting loop ("Please wait...").
- **Branch: Agent assigned:** Returns TwiML `<Dial><Client>{agentId}</Client></Dial>` to route voice audio directly to the agent's WebRTC device.
- **API Endpoint 3:** `GET /api/v1/telephony/twilio/token` with query parameter `agentId` (Targeting `telephony-service`).
  - *Purpose:* Generates short-lived Twilio capability JWTs containing a VoiceGrant. The agent's dashboard calls this to initialize the Twilio Device SDK in the browser.

---

## 11. WebSocket Real-Time Update Flow

**Trigger:** Any Kafka event is published by any service.

```
Any Service          Kafka                WebSocket Gateway           Browser
  │                    │                       │                        │
  │ Publish event      │                       │                        │
  │───────────────────►│                       │                        │
  │                    │ KafkaEventConsumer     │                        │
  │                    │──────────────────────►│                        │
  │                    │                       │ Extract tenantId       │
  │                    │                       │ from JSON payload      │
  │                    │                       │                        │
  │                    │                       │ messagingTemplate      │
  │                    │                       │   .convertAndSend(     │
  │                    │                       │     "/topic/events/"   │
  │                    │                       │     + tenantId,        │
  │                    │                       │     RealtimeEvent)     │
  │                    │                       │                        │
  │                    │                       │ STOMP frame pushed ───►│
  │                    │                       │                        │
  │                    │                       │                        │ SessionStateService
  │                    │                       │                        │   .handleEvent()
  │                    │                       │                        │
  │                    │                       │                        │ Updates UI state
  │                    │                       │                        │ based on topic:
  │                    │                       │                        │  agent-events →
  │                    │                       │                        │    update status badge
  │                    │                       │                        │  routing-events →
  │                    │                       │                        │    show call panel
  │                    │                       │                        │  call-lifecycle →
  │                    │                       │                        │    clear call panel
```

**Granular Code Tracing:**
- **Kafka Consume:** `KafkaEventConsumer.consume(ConsumerRecord<?, ?> record)` in `websocket-gateway` receives messages on topics: `call-events`, `routing-events`, `agent-events`, `call-lifecycle-events`.
  - *Purpose:* Acts as the ingress bridge between the backend Kafka event bus and frontend client WebSockets.
- **Method Called:** `WebSocketGateway.sendToTenant(String tenantId, RealtimeEvent event)`
  - *Purpose:* Deserializes JSON payloads, detects tenant scope, and publishes to Spring STOMP messaging subsystem.
- **Spring WebSocket Pub/Sub Write (Performed by WebSocket Gateway):** `messagingTemplate.convertAndSend("/topic/events/" + tenantId, realtimeEvent)`
  - *Purpose:* In-memory client connection fan-out.
- **Security Check (Performed by WebSocket Gateway):** `AuthChannelInterceptor.preSend(Message<?> message, MessageChannel channel)`
  - *Purpose:* Intercepts initial STOMP `CONNECT` frame, extracts JWT token from `Authorization` header, validates credentials, and sets user context on the socket session.
- **Frontend Action:** Angular's `SessionStateService.subscribe()` connects using SockJS. On payload ingress:
  - Updates agent status badges (`agent-events`).
  - Displays incoming call panel with accept/reject buttons (`routing-events` with status `ASSIGNED`).
  - Closes call control panels (`call-lifecycle-events` with status `CALL_COMPLETED`).

---

## 12. Analytics Update Flow

**Trigger:** Any Kafka event is published.

```
Kafka                  Analytics Service (AnalyticsEventConsumer)          PostgreSQL (tenant_metrics)
  │                              │                                                    │
  │ call-events (isNew=true)     │                                                    │
  │─────────────────────────────►│ incrementTotalCalls(tenant)                        │
  │                              │ incrementQueuedCalls(tenant)                       │
  │                              │───────────────────────────────────────────────────►│
  │                              │   UPDATE tenant_metrics SET total=total+1,         │
  │                              │   queued=queued+1, version=version+1               │
  │                              │                                                    │
  │ routing-events (ASSIGNED)    │                                                    │
  │─────────────────────────────►│ incrementRoutedCalls(tenant)                       │
  │                              │ decrementQueuedCalls(tenant)                       │
  │                              │───────────────────────────────────────────────────►│
  │                              │   UPDATE tenant_metrics SET routed=routed+1,       │
  │                              │   queued=queued-1, version=version+1               │
  │                              │                                                    │
  │ agent-events                 │                                                    │
  │─────────────────────────────►│ updateAgentCounts(tenant, old, new)                │
  │                              │───────────────────────────────────────────────────►│
  │                              │   UPDATE tenant_metrics SET active=active-1,       │
  │                              │   busy=busy+1, version=version+1                   │
```

**Granular Code Tracing:**
- **Kafka Consume:** `AnalyticsEventConsumer.consume(String message, String topic)` in `analytics-service` consumes all relevant state messages.
- **Method Called:** `AnalyticsService.incrementTotalCalls(tenantId)` / `incrementQueuedCalls(tenantId)` / `decrementQueuedCalls(tenantId)` / `incrementRoutedCalls(tenantId)` / `updateAgentCounts(tenantId, oldStatus, newStatus)` / `incrementCompletedCalls(tenantId)`
  - *Purpose:* Translates incoming event transitions to database counter manipulations.
- **PostgreSQL Read (Performed by Analytics Service):** `SELECT * FROM tenant_metrics WHERE tenant_id = ?` via `TenantMetricsRepository.findById(tenantId)`
  - *Purpose:* Checks the current aggregated metric row. A single row per tenant exists.
- **PostgreSQL Write (Optimistic Locking - Performed by Analytics Service):** `UPDATE tenant_metrics SET total_calls = ?, queued_calls = ?, routed_calls = ?, completed_calls = ?, active_agents = ?, busy_agents = ?, offline_agents = ?, version = ? WHERE tenant_id = ? AND version = ?`
  - *Purpose:* Persists metrics. The `@Version` JPA annotation automatically guarantees write serialization. If concurrent event threads collision (throwing `OptimisticLockingFailureException`), the service catches the exception and retries the read-update cycle up to 3 times.
- **Redis Write (Performed by Analytics Service):** None. In-memory counters are managed directly within PostgreSQL rows to protect against analytics loss during container restarts.

---

## 13. Audit Event Flow

**Trigger:** Any Kafka event is published across any topic.

```
Kafka                    Audit Service (KafkaAuditConsumer)         PostgreSQL
  │                              │                                     │
  │ Any event from:              │                                     │
  │  - call-events               │                                     │
  │  - routing-events            │                                     │
  │  - agent-events              │                                     │
  │  - call-lifecycle-events     │                                     │
  │  - user-events               │                                     │
  │─────────────────────────────►│                                     │
  │                              │ Parse JSON payload                  │
  │                              │ Extract: tenantId, eventType,       │
  │                              │   entityId, entityType              │
  │                              │                                     │
  │                              │ INSERT INTO audit_events            │
  │                              │────────────────────────────────────►│
  │                              │   tenantId, eventType,              │
  │                              │   sourceService, entityType,        │
  │                              │   entityId, payloadJson,            │
  │                              │   createdAt                         │
```

**Granular Code Tracing:**
- **Kafka Consume:** `KafkaAuditConsumer.consume(ConsumerRecord<?, ?> record)` in `audit-service` listens to all message streams using wildcard matching pattern or a defined list of topics.
- **Method Called:** `AuditService.logEvent(AuditEventRequest)`
  - *Purpose:* Parses event header details and payload structures.
- **PostgreSQL Write (Performed by Audit Service):** `INSERT INTO audit_events (id, tenant_id, event_type, source_service, entity_type, entity_id, payload_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
  - *Purpose:* Appends a new immutable row in the `audit_events` table in the `minigenesys_audit` database.
- **Immutability Policy:** The service provides no UPDATE or DELETE API interfaces, securing a forensic transaction log.

---

## Summary: Event Flow Map

```
                    ┌──────────────┐
                    │ Call Service  │
                    │              │
              ┌─────┤  call-events ├──────┐
              │     │              │      │
              │     │  call-       │      │
              │     │  lifecycle   │      │
              │     └──────┬───────┘      │
              │            │              │
              ▼            │              ▼
     ┌────────────┐        │     ┌────────────────┐
     │  Routing   │        │     │  Agent State   │
     │  Service   │        │     │   Service      │
     │            │        │     │                │
     │ routing-   │        │     │  agent-events  │
     │ events     │        │     │                │
     └─────┬──────┘        │     └───────┬────────┘
           │               │             │
           ▼               ▼             ▼
    ┌──────────────────────────────────────────┐
    │              Kafka Bus                    │
    │  5 topics, consumed by:                   │
    │   • WebSocket Gateway (push to browser)   │
    │   • Analytics Service (counters)          │
    │   • Audit Service (immutable log)         │
    │   • Cross-service state sync              │
    └──────────────────────────────────────────┘
```

---

## 11. Parallel Fan-Out: What Every Service Does for One Event

> **This is the most important section for interviews.** It shows exactly what happens in EVERY service simultaneously when a single `routing-events` message with status `ASSIGNED` is published.

**Trigger:** `RoutingEngine.assignAgent()` finds agent `AG_001` for call `abc-123` in tenant `tenant1`. It calls `KafkaMessaging.produceRoutingEvent()`.

**Published message:**
```json
{
  "callId": "abc-123",
  "tenantId": "tenant1",
  "agentId": "AG_001",
  "status": "ASSIGNED"
}
```
Topic: `routing-events`, partition key: `"tenant1"`

**All of the following happen CONCURRENTLY (each consumer group gets its own copy):**

---

### Consumer 1: `agent-state-service` (group: `agent-state-service-group`)
**Handler:** `RoutingEventConsumer` → `AgentStateService.handleRoutingEvent(event)`
```
→ event.status == "ASSIGNED" ✅, event.agentId != null ✅ → proceed
→ agentRepository.findByIdAndTenantId("AG_001", "tenant1")  [PG SELECT: agents]
→ agent.status == AVAILABLE (not OFFLINE) → proceed (ping-pong fix)
→ agent.setStatus(BUSY)
→ agent.setActiveCallId("abc-123")
→ agent.setLastAssignedTime(now())
→ agentRepository.save(agent)  [PG UPDATE: agents SET status=BUSY, active_call_id=abc-123]
→ updateRedisState(agent, BUSY)
    opsForHash().put("tenant:tenant1:agent:AG_001:state", "status", "BUSY")
    opsForHash().put("tenant:tenant1:agent:AG_001:state", "lastAssignedTime", now)
    for skill in agent.skills:
      opsForZSet().remove("tenant:tenant1:skill:sales:available", "AG_001")
→ publishEvent(agent, AVAILABLE, BUSY, "AGENT_BUSY")
    → kafkaTemplate.send("agent-events", "tenant1", AgentEvent{AGENT_BUSY, AG_001, ...})
```
**Net state change:** PG: `agents.status = BUSY`. Redis: state hash updated, agent removed from skill sets. Kafka: `agent-events` AGENT_BUSY published.

---

### Consumer 2: `call-service` (group: `call-service-group`)
**Handler:** `RoutingEventConsumer` → `CallService.handleRoutingEvent(event)`
```
→ callRepository.findById("abc-123")  [PG SELECT: calls]
→ tenant safety check: event.tenantId == call.tenantId ✅
→ status == "ASSIGNED"
→ call.setStatus(ROUTED)
→ call.setAssignedAgentId("AG_001")
→ call.setRoutingFailureReason(null)
→ callRepository.save(call)  [PG UPDATE: calls SET status=ROUTED, assigned_agent_id=AG_001]
```
**Net state change:** PG: `calls.status = ROUTED`. No Kafka publish. No Redis.

---

### Consumer 3: `telephony-service` (group: `telephony-service-group`)
**Handler:** `RoutingEventConsumer` → `TelephonyService.handleAssignment(event)`
```
→ event.status == "ASSIGNED" ✅ → proceed
→ repository.findByInternalCallId("abc-123")  [PG SELECT: telephony_call_sessions]
→ if session.assignedAgentId == null (first time):
    session.setAssignedAgentId("AG_001")
    repository.save(session)  [PG UPDATE: telephony_call_sessions SET assigned_agent_id=AG_001]
→ (if no session found: throw RuntimeException → Kafka retry)
```
**Net state change:** PG: `telephony_call_sessions.assigned_agent_id = AG_001`. Next `/bridge` poll returns `<Dial><Client>AG_001</Client></Dial>`.

---

### Consumer 4: `websocket-gateway` (group: `websocket-gateway-group`)
**Handler:** `KafkaEventConsumer.consume(message, topic="routing-events")`
```
→ node = objectMapper.readTree(message)
→ tenantId = "tenant1"
→ event = RealtimeEvent { topic: "routing-events", payload: node, receivedAt: now }
→ messagingTemplate.convertAndSend("/topic/events/tenant1", event)
    → SimpleBroker fans out to all WebSocket subscribers for tenant1
    → AG_001's browser receives: { topic: "routing-events", payload: { status: "ASSIGNED", agentId: "AG_001", ... } }
    → Angular SessionStateService.handleEvent():
        status == "ASSIGNED" && agentId == myAgentId → show call panel, set status "On Call"
```
**Net state change:** Browser UI updated. No DB. No Redis.

---

### Consumer 5: `analytics-service` (group: `analytics-service-group`)
**Handler:** `AnalyticsEventConsumer.consume(message, topic="routing-events")`
```
→ tenantId = "tenant1"
→ handleRoutingEvent("tenant1", node)
→ status == "ASSIGNED"
→ analyticsService.incrementRoutedCalls("tenant1")
    → updateMetric("tenant1", m -> m.setRoutedCalls(m.getRoutedCalls() + 1))
    → [PG UPDATE: tenant_metrics SET routed_calls = routed_calls + 1 WHERE tenant_id = 'tenant1']
→ analyticsService.decrementQueuedCalls("tenant1")
    → updateMetric("tenant1", m -> m.setQueuedCalls(Math.max(0, m.getQueuedCalls() - 1)))
    → [PG UPDATE: tenant_metrics SET queued_calls = queued_calls - 1 WHERE tenant_id = 'tenant1']
```
**Net state change:** PG: `tenant_metrics.routed_calls++`, `queued_calls--`.

---

### Consumer 6: `audit-service` (group: `audit-service-group`)
**Handler:** `KafkaAuditConsumer.consume(message, topic="routing-events")` [@Transactional]
```
→ node = objectMapper.readTree(message)
→ tenantId = "tenant1"
→ eventType = "ASSIGNED"  (no "eventType" field → fallback to... wait, routing-events have "status" not "eventType")
         Actually: node.has("eventType") = false → eventType = topic = "routing-events"
→ node.has("callId") = true → entityType = "CALL", entityId = "abc-123"
→ sourceService = getSourceService("routing-events") = "routing-service"
→ AuditEvent { tenantId, eventType="routing-events", sourceService="routing-service",
               entityType="CALL", entityId="abc-123", payloadJson=<full JSON> }
→ auditRepository.save(event)  [PG INSERT: audit_events]
```
**Net state change:** PG: new row in `audit_events` for this ASSIGNED event.

---

### Summary Table: One ASSIGNED Event, Six Consumers

| Service | Method | DB Write | Redis Write | Kafka Out |
|---|---|---|---|---|
| agent-state-service | `AgentStateService.handleRoutingEvent()` | `agents`: status=BUSY | state hash + skill ZREM | `agent-events` AGENT_BUSY |
| call-service | `CallService.handleRoutingEvent()` | `calls`: status=ROUTED | none | none |
| telephony-service | `TelephonyService.handleAssignment()` | `telephony_call_sessions`: assigned_agent_id | none | none |
| websocket-gateway | `KafkaEventConsumer.consume()` | none | none | none (WebSocket push) |
| analytics-service | `AnalyticsEventConsumer.consume()` | `tenant_metrics`: routedCalls++, queuedCalls-- | none | none |
| audit-service | `KafkaAuditConsumer.consume()` | `audit_events`: INSERT | none | none |

