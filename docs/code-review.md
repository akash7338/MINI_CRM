# Code Review: Campaign and Queue Routing Implementation

This report details a comprehensive code review of the Campaign and Queue routing implementation in the MiniGenesys codebase. The goal of this audit is to ensure the correctness of the new features, confirm they do not break existing business logic (such as Twilio-based skill routing), identify race conditions or architectural bottlenecks, and provide recommendations.

---

## Executive Summary

The campaign and queue routing additions are well-structured, clean, and follow the established Spring Boot, Redis, and Kafka architecture. 
* **Business Logic Safety**: The Twilio/FreeSWITCH routing fallbacks are intact. Inbound calls that do not match any campaign/queue correctly fall back to skill-based routing.
* **Database & ORM**: The JPA entities for Campaign and Queue map properly and do not conflict under Hibernate's `ddl-auto: update`.
* **Identified Issues**: We have identified two critical bugs (a **race condition that causes double-assignment** of agents and a **Head-of-Line blocking** issue in the retry processor), along with two code smells (a **Redis sync delay** on queue updates and a **hardcoded service URL**).

Below, we detail each finding and provide concrete, drop-in code fixes.

---

## 1. Correctness & Bug Audit

### 🔴 Critical Bug 1: Redis Race Condition (Double-Assignment of Multi-Queue Agents)
**Component**: `routing-service` — [RoutingEngine.java](file:///Users/akash.singh/Desktop/MiniGenesys/backend/routing-service/src/main/java/com/minigenesys/routing/engine/RoutingEngine.java#L32-L46)

#### Problem Analysis
When an agent is in multiple queues (e.g., Queue A and Queue B) or has multiple skills, they are added to multiple Redis sorted sets:
* `tenant:{tenantId}:queue:QueueA:available`
* `tenant:{tenantId}:queue:QueueB:available`

When a call for Queue A arrives, the Lua script intersects the keys for Queue A, selects the agent, sets their state to `BUSY` in the hash `tenant:{tenantId}:agent:{agentId}:state`, and removes them from Queue A's set. 
However, **the agent is NOT removed from Queue B's set in the Lua script**, because Queue B's key was not passed in `KEYS`!
Although `agent-state-service` asynchronously cleans up the remaining sets when it processes the `RoutingEvent` via Kafka, this introduces a **race condition window** (tens/hundreds of milliseconds). If a call for Queue B arrives during this window:
1. `RoutingEngine` runs the Lua script for Queue B.
2. The Lua script intersects Queue B's available set and finds the agent (since they haven't been removed from Queue B yet).
3. The Lua script selects them, sets their status to `BUSY` again, and returns the agent ID.
4. **Result**: The agent is double-assigned to two concurrent calls.

#### Proposed Solution
Update the Lua script to verify the agent's status in the Redis state hash (`HGET stateKey status`) before selecting them. If the agent's status is not `AVAILABLE`, they are stale. The script should remove them from all intersected sets and check the next candidate.

```diff
-    private static final String SELECT_AGENT_LUA = 
-        "local tenantId = ARGV[1]; " +
-        "local agentStateTpl = ARGV[2]; " +
-        "local timestamp = ARGV[3]; " +
-        "local tempSet = 'temp:routing:' .. ARGV[4]; " +
-        "redis.call('ZINTERSTORE', tempSet, #KEYS, unpack(KEYS)); " +
-        "local agent = redis.call('ZRANGE', tempSet, 0, 0)[1]; " +
-        "redis.call('DEL', tempSet); " +
-        "if agent then " +
-        "  for i, key in ipairs(KEYS) do redis.call('ZREM', key, agent) end; " +
-        "  local stateKey = string.format(agentStateTpl, tenantId, agent); " +
-        "  redis.call('HSET', stateKey, 'status', 'BUSY', 'lastAssignedTime', timestamp); " +
-        "  return agent; " +
-        "end; " +
-        "return nil; ";
+    private static final String SELECT_AGENT_LUA = 
+        "local tenantId = ARGV[1]; " +
+        "local agentStateTpl = ARGV[2]; " +
+        "local timestamp = ARGV[3]; " +
+        "local tempSet = 'temp:routing:' .. ARGV[4]; " +
+        "redis.call('ZINTERSTORE', tempSet, #KEYS, unpack(KEYS)); " +
+        "local agents = redis.call('ZRANGE', tempSet, 0, -1); " +
+        "redis.call('DEL', tempSet); " +
+        "for _, agent in ipairs(agents) do " +
+        "  local stateKey = string.format(agentStateTpl, tenantId, agent); " +
+        "  local status = redis.call('HGET', stateKey, 'status'); " +
+        "  if status == 'AVAILABLE' then " +
+        "    for i, key in ipairs(KEYS) do redis.call('ZREM', key, agent) end; " +
+        "    redis.call('HSET', stateKey, 'status', 'BUSY', 'lastAssignedTime', timestamp); " +
+        "    return agent; " +
+        "  else " +
+        "    for i, key in ipairs(KEYS) do redis.call('ZREM', key, agent) end; " +
+        "  end " +
+        "end; " +
+        "return nil; ";
```

---

### 🔴 Critical Bug 2: Head-of-Line (HoL) Blocking on Retry Failure
**Component**: `routing-service` — [RetryProcessor.java](file:///Users/akash.singh/Desktop/MiniGenesys/backend/routing-service/src/main/java/com/minigenesys/routing/service/RetryProcessor.java#L105-L107)

#### Problem Analysis
`RetryProcessor.processTenantQueue` pulls all queued calls for a tenant from a single sorted set (`tenant:{tenantId}:call:queue`).
If the call at the head of the queue (highest priority) fails to route because no matching agent is online, `assignAgent` returns `NO_AGENT`. 
The `RetryProcessor` then executes:
```java
if ("NO_AGENT".equals(result.getStatus())) {
    break;
}
```
Breaking the loop halts retry processing for **all remaining calls in the tenant's queue** during that run.
If Call 1 requires "Spanish" (and no Spanish agents are available) and Call 2 requires "English" (with English agents available), Call 2 is blocked and will never route until a Spanish agent logs in. This is a severe Head-of-Line blocking regression.

#### Proposed Solution
Change `break` to `continue` so that subsequent calls with different skill or queue requirements are allowed to route when the head of the queue is blocked.

```diff
-                // If NO_AGENT, stop this tenant's queue — lower priority calls won't match either
-                if ("NO_AGENT".equals(result.getStatus())) {
-                    break;
-                }
+                // If NO_AGENT, continue to the next call in the queue
+                if ("NO_AGENT".equals(result.getStatus())) {
+                    continue;
+                }
```

---

### 🟡 Bug/Limitation 3: Queue Membership Sync Delay
**Component**: `agent-state-service` — [QueueController.java](file:///Users/akash.singh/Desktop/MiniGenesys/backend/agent-state-service/src/main/java/com/minigenesys/agentstate/controller/QueueController.java)

#### Problem Analysis
When an administrator updates a queue's members via `PUT /api/v1/queues/{id}`, the controller successfully updates the database entity (`Queue` and `Agent.queueIds`).
However, **it does not modify the Redis availability sets** (`tenant:{tenantId}:queue:{queueId}:available`) for agents who are already online.
An agent who is currently `AVAILABLE` will not be added to the queue's Redis set until they cycle their state (e.g., toggle to Offline and back to Available). Similarly, removed agents will continue receiving calls from the queue until their state changes.

#### Proposed Solution
Inject `StringRedisTemplate` into `QueueController` and immediately synchronize the Redis sets when queue memberships are updated:
1. For agents removed from the queue: delete their entry from `tenant:{tenantId}:queue:{queueId}:available`.
2. For agents added to the queue: check if they are currently `AVAILABLE`. If so, add them to `tenant:{tenantId}:queue:{queueId}:available` with their current last assigned time score.

---

### 🟡 Code Smell 4: Hardcoded Queue Service URL
**Component**: `call-service` — [QueueServiceClient.java](file:///Users/akash.singh/Desktop/MiniGenesys/backend/call-service/src/main/java/com/minigenesys/callservice/client/QueueServiceClient.java#L26)

#### Problem Analysis
`QueueServiceClient` calls the queue endpoint using a hardcoded `localhost` address:
```java
"http://localhost:8086/api/v1/queues/" + queueId
```
This breaks deployment in multi-container environments (like Docker Compose or Kubernetes) where microservices communicate via service names (e.g., `http://agent-state-service:8086`).

#### Proposed Solution
Make the URL configurable in `application.yml` and inject it using Spring's `@Value` annotation.

**In `application.yml`**:
```yaml
services:
  agent-state:
    url: ${AGENT_STATE_SERVICE_URL:http://localhost:8086}
```

**In `QueueServiceClient.java`**:
```java
@Value("${services.agent-state.url}")
private String agentStateServiceUrl;

// inside getQueue:
agentStateServiceUrl + "/api/v1/queues/" + queueId
```

---

### 🟡 Design Recommendation 5: Call Validation in Call Service
**Component**: `call-service` — [CallService.java](file:///Users/akash.singh/Desktop/MiniGenesys/backend/call-service/src/main/java/com/minigenesys/callservice/service/CallService.java#L29)

#### Problem Analysis
`CallService.createCall` saves `request.getCampaignId()` and `request.getQueueId()` into the database without checking if they actually exist.
If an invalid `queueId` is provided, the call is queued. During routing, `RoutingEngine` tries to intersect with `tenant:{tenantId}:queue:{invalidQueueId}:available`, which will be empty, causing the call to retry 10 times and eventually fail silently with `ABANDONED`.

#### Proposed Solution
Validate `campaignId` and `queueId` during call creation and throw a `400 Bad Request` if they do not exist:
* Validate `campaignId` using `CampaignRepository.findByIdAndTenantId`.
* Validate `queueId` using `QueueServiceClient` (which now correctly returns a 404/null if the queue does not exist).

---

## 2. ORM & Schema Compatibility

### ElementCollection Configuration
In [Agent.java](file:///Users/akash.singh/Desktop/MiniGenesys/backend/agent-state-service/src/main/java/com/minigenesys/agentstate/model/Agent.java#L43-L46) and [Queue.java](file:///Users/akash.singh/Desktop/MiniGenesys/backend/agent-state-service/src/main/java/com/minigenesys/agentstate/model/Queue.java#L28-L31), the relationships are defined via `@ElementCollection(fetch = FetchType.EAGER)`.

* **Compatibility under `ddl-auto: update`**: Hibernate is fully compatible with this setup. It will automatically issue `CREATE TABLE agent_queues` and `CREATE TABLE queue_agents`.
* **Redundant Join Tables**: Because both entities track the association via `@ElementCollection`, the database ends up with two separate join tables storing the exact same agent-queue mappings. While this works due to manual synchronization inside `QueueController`, a cleaner design would use a single joint table via a standard JPA `@ManyToMany` relationship:
  * This would avoid manual syncing code.
  * However, the current manual-sync implementation is functional and does not break business logic.

---

## 3. Business Logic Preservation

### Twilio Routing Fallback
We verified that the campaign/queue logic does not affect standard Twilio skill-based routing.
* **Telephony Provider Isolation**: The routing logic continues to pass `telephonyProvider` (e.g., `TWILIO` or `FREESWITCH`) through Kafka events and DB fields.
* **Skill Routing Fallback**: If a call does not specify a `queueId` or a `campaignId` (as is the case with standard Twilio calls that use skill-based IVR), the `disableSkills` flag defaults to `false`, and the Lua script intersects the normal skill keys (`tenant:{tenantId}:skill:{skill}:available`). The routing path remains identical to the pre-campaign implementation.
* **Queue Skill Disabling**: If a queue specifies `disableSkills = true`, the routing engine correctly skips adding skill keys to the intersect array, which is the desired behavior for strict FIFO queue-only routing.
