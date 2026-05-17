# Routing Service

## 1. One-Line Purpose

The brain of the contact center — consumes incoming call events, atomically selects the best available agent using a Redis Lua script with skill-matching and LRU scheduling, and retries unmatched calls with Fibonacci backoff.

---

## 2. When This Service Comes Into Picture

1. **New call arrives** — Consumes `call-events` from Kafka, immediately tries to find an agent
2. **Call requeued after disconnect** — Same flow, but the call has `isNew: false`
3. **No agent found** — Enqueues the call in Redis and starts the retry loop
4. **Every 5 seconds** — `RetryProcessor` scans all tenant queues and retries backoff-eligible calls
5. **Max retries exceeded** — Abandons the call after 10 retries (~114 seconds)

---

## 3. Responsibilities

1. **Call-to-Agent Matching** — Uses a Redis Lua script to atomically intersect skill sets, select the least-recently-used agent, remove them from availability, and mark them BUSY
2. **Distributed Locking** — Acquires a Redis lock per call ID to prevent duplicate routing
3. **Idempotency Cache** — Remembers previous assignments in Redis (1-hour TTL) to handle duplicate Kafka messages
4. **Retry Queue Management** — `QueueManager` maintains a priority-sorted queue per tenant in Redis
5. **Fibonacci Backoff Retries** — `RetryProcessor` retries queued calls with increasing delays (1s, 1s, 2s, 3s, 5s, 8s, 13s, 21s, 30s, 30s)
6. **Call Abandonment** — After 10 failed retries, publishes ABANDONED event
7. **Event Publishing** — Publishes ASSIGNED, NO_AGENT, or ABANDONED to `routing-events` Kafka topic

---

## 4. APIs Exposed

The routing-service has **no externally exposed APIs**. It is entirely event-driven:

- Consumes from Kafka `call-events` topic
- Produces to Kafka `routing-events` topic
- Uses a `@Scheduled` method for the retry loop

There is no REST controller that external clients can call.

---

## 5. Kafka Usage

### Consumes ← `call-events`
- **Consumer:** `KafkaMessaging.consumeCallEvent()` (group: `routing-service-group`)
- **Calls:** `routingService.processRouting(request)` → `routingEngine.assignAgent(request)`
- **If `NO_AGENT`:** calls `queueManager.enqueue(request)` and logs call ID
- **On exception:** rethrows as `RuntimeException` to trigger Kafka DLQ

### Produces → `routing-events`
**Method:** `KafkaMessaging.produceRoutingEvent(AssignmentResult result)`
- Serializes `AssignmentResult` to JSON
- `kafkaTemplate.send(ROUTING_EVENTS_TOPIC, result.getTenantId(), message).get()` — **blocking call** to ensure publish success before releasing lock

| Status | When | Consumers |
|---|---|---|
| `ASSIGNED` | Agent found and claimed | agent-state-service, call-service, telephony-service, websocket-gateway, analytics, audit |
| `NO_AGENT` | No available agent matches skills | call-service (updates call to QUEUED), analytics |
| `ABANDONED` | Max retries (10) exceeded | call-service (updates call to ABANDONED + optionally frees agent), analytics, audit |

---

## 6. Redis Usage

### Routing Engine Keys

| Key Pattern | Type | Purpose |
|---|---|---|
| `routing:lock:call:{callId}` | String | Distributed lock (10s TTL). Prevents two consumers from routing the same call simultaneously |
| `routing:assignment:call:{callId}` | String | Idempotency cache. Value = agentId. TTL = 1 hour. Prevents duplicate assignments from duplicate Kafka messages |
| `tenant:{id}:skill:{skill}:available` | Sorted Set | **Read by Lua script.** Available agents per skill, scored by lastAssignedTime |
| `tenant:{id}:agent:{id}:state` | Hash | **Written by Lua script.** Sets agent status to BUSY and updates lastAssignedTime |

### Queue Manager Keys

| Key Pattern | Type | Purpose |
|---|---|---|
| `tenant:{id}:call:queue` | Sorted Set | Priority queue. Score = `(-priority * 10^13) + timestamp`. Lower score = higher priority |
| `tenant:{id}:call:{callId}` | String (JSON) | Full call request payload for retry processing |
| `tenant:{id}:call:{callId}:retries` | String (counter) | Number of retry attempts |
| `tenant:{id}:call:{callId}:lastRetryAt` | String (epoch ms) | Timestamp of last retry attempt |
| `routing:active-tenants` | Set | Set of tenant IDs that currently have calls in queue |

### Priority Score Formula
```
score = (-priority * 10,000,000,000,000) + timestamp
```
- Higher priority → more negative score → sorted first in ZRANGE
- Same priority → earlier timestamp → sorted first (FIFO)

---

## 7. PostgreSQL Usage

### Database: `minigenesys_routing`

### Table: `assignments`
| Column | Type | Description |
|---|---|---|
| `id` | UUID (PK, auto-generated) | Assignment record ID |
| `call_id` | VARCHAR (unique) | The call that was assigned |
| `agent_id` | VARCHAR | The agent it was assigned to |
| `tenant_id` | VARCHAR | Multi-tenant scope |
| `assigned_at` | TIMESTAMP | When the assignment was made |

This table is a **historical record** of all assignments. It's written to during `saveAssignment()` but never read during the hot path (routing decisions are made entirely in Redis).

---

## 8. Important State Changes

### Routing Decision Flow
```
call-events message arrives
         │
         ▼
  Acquire distributed lock
         │
         ▼
  Check idempotency cache ──── HIT ──► Verify agent is still online
         │                                    │
         │ MISS                          Online? → Return cached assignment
         │                              Offline? → Delete cache, continue ↓
         ▼
  Execute Lua script
         │
    ┌────┴─────┐
    │          │
  Agent      No agent
  found      found
    │          │
    ▼          ▼
  Save to PG   Return NO_AGENT
  Cache in     Enqueue in Redis
  Redis        for retry
    │
    ▼
  Publish ASSIGNED
  to routing-events
```

### Retry Flow
```
RetryProcessor (every 5 seconds)
         │
         ▼
  Get all tenants with queues
         │
         ▼
  For each tenant:
    Get all queued call IDs
         │
         ▼
    For each call:
      ├── retryCount >= 10? → ABANDON, dequeue
      ├── Backoff not elapsed? → SKIP
      └── Try assignAgent()
            ├── SUCCESS → dequeue, publish ASSIGNED
            └── FAIL (NO_AGENT) → increment retry, BREAK tenant loop
```

**Why BREAK on NO_AGENT?** If the highest-priority call can't find an agent, lower-priority calls won't either. This optimization prevents wasting Redis queries.

---

## 9. Interaction With Other Services

| Direction | Service | How | Why |
|---|---|---|---|
| **Consumes ←** | Call Service | Kafka `call-events` | Receives new/requeued calls to route |
| **Produces →** | Kafka `routing-events` | ASSIGNED/NO_AGENT/ABANDONED | Consumed by agent-state, call-service, telephony, websocket, analytics, audit |
| **Reads ←** | Redis | Skill sorted sets, agent state hashes | The Lua script reads agent availability data written by agent-state-service |
| **Writes →** | Redis | Agent state hash (BUSY), skill sets (ZREM) | The Lua script atomically claims an agent |
| **Writes →** | PostgreSQL | assignments table | Durable record of every assignment |

### Important: Shared Redis State
The routing-service and agent-state-service **share the same Redis instance** and operate on the same keys. The Lua script writes to `tenant:{id}:agent:{id}:state` and removes agents from `tenant:{id}:skill:{skill}:available` — these are the same keys that agent-state-service manages. This is a deliberate design choice for atomicity (the Lua script needs to read + write in a single operation), but it means both services must agree on the key format.

---

## 10. Edge Cases / Failure Scenarios

| Scenario | What Happens |
|---|---|
| **Duplicate Kafka message for same call** | Distributed lock prevents concurrent processing. If the lock is held, returns `LOCKED` (harmless). If the lock is free but the idempotency cache exists, returns the cached assignment |
| **Idempotency cache points to offline agent (THE BUG WE FIXED)** | Before fix: blindly returned the cached agent, causing the ping-pong loop. After fix: checks agent's Redis state hash, and if the agent is offline, deletes the cache and re-routes |
| **Redis down** | Lua script fails → routing returns ERROR → call-service sets call to FAILED |
| **All agents go offline during retry** | Retries continue to fail with NO_AGENT. After 10 retries (~114s), call is ABANDONED |
| **Two calls arrive for the same single agent** | The Lua script atomically claims the agent for the first call and removes them from skill sets. The second call finds an empty set and gets NO_AGENT |
| **Call requeued while already in retry queue** | The QueueManager overwrites the existing queue entry (same call ID, same sorted set). The retry count may reset if the call data is overwritten |
| **RetryProcessor and KafkaMessaging both try to route the same call** | The distributed lock (`routing:lock:call:{callId}`) ensures only one wins |

### The Lua Script (Critical Path)
```lua
-- 1. Intersect all skill sorted sets into a temp set
ZINTERSTORE tempSet, #KEYS, skill1Set, skill2Set, ...

-- 2. Pick the agent with the lowest score (least recently used)
agent = ZRANGE tempSet 0 0

-- 3. Clean up temp set
DEL tempSet

-- 4. If agent found:
--    Remove from ALL skill sets (so no one else grabs them)
--    Set their state hash to BUSY
for each skillKey: ZREM skillKey agent
HSET stateKey status BUSY lastAssignedTime now

return agent  -- or nil if no match
```

This entire script executes atomically on Redis — no other command can interleave.

---

## 11. Interview Explanation

> "The routing-service is the core intelligence of the platform. It's entirely event-driven — no REST APIs. When a call-event arrives from Kafka, it runs a Redis Lua script that atomically finds the best agent across skill-based sorted sets using LRU scheduling. The Lua script does four things in a single atomic operation: intersects multiple skill sets to find agents matching all required skills, picks the one with the oldest last-assigned time for fairness, removes them from all availability sets so no other call can grab them, and marks them BUSY. If no agent is available, the call goes into a priority queue in Redis with Fibonacci backoff retries — 1s, 1s, 2s, 3s, up to 30s — for a maximum of 10 retries before abandoning. I also fixed a critical idempotency cache bug where the cache blindly returned an offline agent's ID without checking their current status, causing an infinite assign-disconnect-requeue loop. The fix validates the cached agent's Redis state before trusting the cache."

### Key Technical Details Worth Mentioning
1. **Lua scripts run atomically on Redis** — no race conditions possible during agent selection
2. **Priority scoring formula** — `(-priority * 10^13) + timestamp` ensures higher priority calls are always dequeued first, with FIFO ordering within the same priority
3. **The distributed lock uses a Lua-based CAS delete** — `if redis.call('get', KEYS[1]) == ARGV[1] then del` — to prevent accidentally deleting another thread's lock
4. **The idempotency cache exists because Kafka delivers "at least once"** — duplicate messages would cause duplicate assignments without it

---

## 12. Annotated Flow Trace (Exact Methods)

### Primary Path: Call Arrives
```
Kafka: call-events message (JSON CallRequest)
→ KafkaMessaging.consumeCallEvent(String message)
    → objectMapper.readValue(message, CallRequest.class)
    → routingService.processRouting(request)
        → routingEngine.assignAgent(request)  ← ALL THE WORK HAPPENS HERE
    → KafkaMessaging.produceRoutingEvent(result)
        → kafkaTemplate.send("routing-events", tenantId, json).get()  [blocking]
    → if result.status == "NO_AGENT":
        queueManager.enqueue(request)  ← Redis retry queue
    → on exception: throw RuntimeException  → Kafka DLQ
```

### Inside `RoutingEngine.assignAgent(CallRequest call)`
```
Step 1: Acquire distributed lock
  lockKey = "routing:lock:call:" + callId
  lockToken = UUID.randomUUID().toString()
  redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, 10, SECONDS)
  if NOT acquired: return AssignmentResult.failure(callId, tenantId, "LOCKED", ...)

Step 2: Idempotency check
  cachedAgentId = redisTemplate.opsForValue().get("routing:assignment:call:" + callId)
  if cachedAgentId != null:
    agentStateKey = "tenant:%s:agent:%s:state" % (tenantId, cachedAgentId)
    currentStatus = redisTemplate.opsForHash().get(agentStateKey, "status")
    if currentStatus in ["BUSY", "AVAILABLE"]:
      return AssignmentResult.success(callId, tenantId, cachedAgentId)  ← cache hit
    else:  ← THE BUG FIX: agent is offline, don't trust the cache
      log.warn("agent offline, clearing idempotency cache")
      redisTemplate.delete("routing:assignment:call:" + callId)

Step 3: Execute Lua script (atomically)
  skillKeys = request.requiredSkills.map(s → "tenant:X:skill:" + s + ":available")
  selectedAgentId = redisTemplate.execute(
    new DefaultRedisScript(SELECT_AGENT_LUA, String.class),
    skillKeys,                               // KEYS[]
    tenantId, AGENT_STATE_KEY_TPL, now, uuid // ARGV[]
  )

  Lua script (runs atomically on Redis):
    ZINTERSTORE tempSet [skill1Set, skill2Set, ...]  → agents with ALL required skills
    agent = ZRANGE tempSet 0 0  → pick lowest score (least recently assigned)
    DEL tempSet
    if agent found:
      for each skillKey: ZREM skillKey agent   → remove from ALL skill pools
      HSET stateKey 'status' 'BUSY' 'lastAssignedTime' now   → mark BUSY
      return agent
    return nil

Step 4 (if agent found): Save and cache
  RoutingEngine.saveAssignment(call, selectedAgentId):
    assignmentRepository.findByCallId(callId) orCreate  [PG SELECT/INSERT]
    assignment.setAgentId(selectedAgentId)
    assignmentRepository.save()  [PG UPSERT: assignments table]
    redisTemplate.opsForValue().set(
        "routing:assignment:call:" + callId, selectedAgentId, 1, HOURS)
  return AssignmentResult.success(callId, tenantId, selectedAgentId)

Step 4 (if no agent): Return failure
  return AssignmentResult.failure(callId, tenantId, "NO_AGENT", "No available agent matches skills")

Finally (always): Release lock using CAS Lua
  Lua: if redis.call('get', lockKey) == lockToken then redis.call('del', lockKey) end
  (CAS delete prevents accidentally deleting a different thread's lock)
```

