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

**What happens in code:**
- `UserService.authenticate()` validates credentials against PostgreSQL `users` table
- JWT is signed with a shared secret, contains `tenantId` and `role` claims
- Frontend stores the token in `localStorage` and attaches it to every subsequent request via `ApiService`

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

**What happens in code:**
1. `AgentStateService.changeState()` validates the transition `OFFLINE → AVAILABLE`
2. `updateRedisState()` writes the agent's state hash and adds them to skill-based sorted sets
3. `publishEvent()` sends `AGENT_AVAILABLE` to Kafka `agent-events` topic
4. Frontend's `SessionStateService.startHeartbeat()` begins sending pings every 15 seconds

**Redis keys created:**
- `tenant:tenant1:agent:AG_001:state` → Hash `{status: AVAILABLE, lastAssignedTime: ...}`
- `tenant:tenant1:skill:sales:available` → Sorted Set, member `AG_001` scored by lastAssignedTime
- `tenant:tenant1:agent:AG_001:heartbeat` → String with TTL 30s

---

## 3. Agent Ready Flow

**Trigger:** Agent clicks "Set Ready" after completing a call (transitions from `BUSY → AVAILABLE`).

This is identical to the login flow but with a different state transition:
- `AgentStateService.changeState(BUSY → AVAILABLE)` is called
- Agent is re-added to the Redis skill sorted sets (making them routable again)
- `AGENT_AVAILABLE` event published to Kafka
- WebSocket pushes the status change to the browser

**Important:** This transition normally happens **automatically** when the `call-lifecycle-events` topic delivers a `CALL_COMPLETED` event. The `AgentStateService.handleCallCompletion()` method handles this.

---

## 4. Call Creation Flow

**Trigger:** Agent clicks "Simulate Call" on the dashboard, OR a real Twilio inbound call arrives.

### Simulated Call Path (Dashboard)
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

### Real Twilio Inbound Call Path
```
Twilio                   Telephony Service       Call Service            Kafka
  │                           │                       │                    │
  │ POST /twilio/inbound      │                       │                    │
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

**What happens in code:**
- `CallService.createCall()` persists the call with `status = QUEUED`
- `CallEventProducer.publishCallEvent()` sends the call to `call-events` Kafka topic
- The call-event contains `callId`, `tenantId`, `requiredSkills`, `priority`, and `isNew: true`

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

**The Lua Script (atomic operation):**
The `SELECT_AGENT_LUA` script in `RoutingEngine.java` does all of this in one atomic Redis operation:
1. `ZINTERSTORE` — intersects all skill sorted sets to find agents matching ALL required skills
2. `ZRANGE ... 0 0` — picks the agent with the lowest score (least recently used)
3. `ZREM` — removes the agent from all skill sets (so no other call can grab them)
4. `HSET` — marks the agent as BUSY in their state hash

**Downstream effects (via Kafka consumers):**
- `agent-state-service` consumes `routing-events` → updates PostgreSQL agent record to BUSY
- `call-service` consumes `routing-events` → updates call status to ROUTED
- `telephony-service` consumes `routing-events` → stores the assigned agent ID for Twilio bridging
- `websocket-gateway` consumes `routing-events` → pushes `CALL_ASSIGNED` to the agent's browser
- `analytics-service` consumes `routing-events` → increments routed call counter
- `audit-service` consumes `routing-events` → persists the event as an audit record

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

**Fibonacci Backoff Delays:**
```
Retry 0: 1 second
Retry 1: 1 second
Retry 2: 2 seconds
Retry 3: 3 seconds
Retry 4: 5 seconds
Retry 5: 8 seconds
Retry 6: 13 seconds
Retry 7: 21 seconds
Retry 8: 30 seconds
Retry 9: 30 seconds
Total:   ~114 seconds before ABANDONED
```

**Important behavior:** If the retry gets `NO_AGENT` for a tenant, it `break`s out of the loop for that tenant — because if the highest-priority call can't find an agent, lower-priority calls won't either.

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
  │ ◄── WebSocket: CALL_COMPLETED ───│                      │                       │
  │ ◄── WebSocket: AGENT_AVAILABLE ──│                      │                       │
```

**Call Status State Machine:**
```
QUEUED → ROUTED → IN_PROGRESS → COMPLETED
                                     ↓
                                  FAILED
```

- `QUEUED → ROUTED`: Set by call-service when it consumes an `ASSIGNED` routing-event
- `ROUTED → IN_PROGRESS`: Set by `CallService.updateCallStatus()` (REST call from browser or Twilio)
- `IN_PROGRESS → COMPLETED`: Set by `CallService.updateCallStatus()`

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

**Polling loop:** If the agent hasn't been assigned yet when Twilio hits `/bridge`, it returns TwiML that says "Your call is still in queue" with a 3-second pause and a redirect back to `/bridge`, creating a polling loop until an agent is assigned.

**Token generation:** The agent's browser calls `GET /twilio/token?agentId=AG_001` to get a Twilio Access Token with a VoiceGrant, enabling WebRTC audio.

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

**Topics the WebSocket Gateway subscribes to:**
- `call-events`
- `routing-events`
- `agent-events`
- `call-lifecycle-events`

**Frontend routing logic in `SessionStateService.handleEvent()`:**
- `agent-events` with matching `agentId` → update agent status badge
- `routing-events` with `ASSIGNED` and matching `agentId` → show call panel, set status "On Call"
- `call-lifecycle-events` with `CALL_COMPLETED` → clear call panel after 3s delay, set status "Ready"

**Connection:** Uses STOMP over SockJS. The `AuthChannelInterceptor` validates the JWT on the WebSocket CONNECT frame.

---

## 12. Analytics Update Flow

**Trigger:** Any Kafka event is published.

```
Kafka                  Analytics Service (AnalyticsEventConsumer)          Redis
  │                              │                                          │
  │ call-events (isNew=true)     │                                          │
  │─────────────────────────────►│ incrementTotalCalls(tenant)              │
  │                              │ incrementQueuedCalls(tenant)             │
  │                              │─────────────────────────────────────────►│
  │                              │   INCR analytics:tenant1:totalCalls      │
  │                              │   INCR analytics:tenant1:queuedCalls     │
  │                              │                                          │
  │ routing-events (ASSIGNED)    │                                          │
  │─────────────────────────────►│ incrementRoutedCalls(tenant)             │
  │                              │ decrementQueuedCalls(tenant)             │
  │                              │─────────────────────────────────────────►│
  │                              │   INCR analytics:tenant1:routedCalls     │
  │                              │   DECR analytics:tenant1:queuedCalls     │
  │                              │                                          │
  │ routing-events (ABANDONED)   │                                          │
  │─────────────────────────────►│ incrementAbandonedCalls(tenant)          │
  │                              │ decrementQueuedCalls(tenant)             │
  │                              │                                          │
  │ agent-events                 │                                          │
  │─────────────────────────────►│ updateAgentCounts(tenant, old, new)      │
  │                              │─────────────────────────────────────────►│
  │                              │   DECR analytics:tenant1:agents:AVAILABLE│
  │                              │   INCR analytics:tenant1:agents:BUSY     │
  │                              │                                          │
  │ call-lifecycle (COMPLETED)   │                                          │
  │─────────────────────────────►│ incrementCompletedCalls(tenant)          │
  │                              │─────────────────────────────────────────►│
  │                              │   INCR analytics:tenant1:completedCalls  │
```

**Important:** Analytics uses Redis counters only (no PostgreSQL). This makes reads extremely fast for the dashboard but means counters can drift if events are processed out of order or duplicated.

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

**Source service mapping:**
- `call-events` / `call-lifecycle-events` → `"call-service"`
- `routing-events` → `"routing-service"`
- `agent-events` → `"agent-state-service"`
- `user-events` → `"user-service"`

**The audit trail is immutable.** Events are only ever inserted, never updated or deleted. This provides a complete forensic history of everything that happened in the system.

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
