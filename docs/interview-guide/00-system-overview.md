# Mini Genesys — System Overview

## What Is This Project?

Mini Genesys is a **multi-tenant cloud contact center platform** built with a microservices architecture. It handles real-time routing of customer calls to available agents based on skill-matching, manages agent availability states, provides real-time dashboard updates via WebSockets, and integrates with Twilio for actual telephony (WebRTC voice calls).

Think of it as a simplified version of Genesys Cloud or Amazon Connect — the software that powers call centers.

---

## Architecture at a Glance

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Angular Dashboard (UI)                       │
│         Login · Agent Panel · Call Controls · Activity Log          │
└──────────┬──────────────────────────────┬───────────────────────────┘
           │ HTTP (REST)                  │ WebSocket (STOMP)
           ▼                              ▼
┌──────────────────┐            ┌─────────────────────┐
│   API Gateway    │            │  WebSocket Gateway  │
│   (Port 8080)    │            │    (Port 8085)      │
│  JWT validation  │            │  Kafka → Browser    │
│  Route proxying  │            │  Real-time push     │
└──────┬───────────┘            └─────────────────────┘
       │                                  ▲
       │ Routes to:                       │ Consumes from Kafka
       │                                  │
       ├──────────────┬───────────────────┼──────────────────┐
       ▼              ▼                   │                  ▼
┌──────────────┐  ┌──────────────┐  ┌─────┴────────┐  ┌──────────────┐
│ User Service │  │ Agent State  │  │   Routing    │  │ Call Service │
│  (Port 8081) │  │   Service    │  │   Service    │  │  (Port 8087) │
│              │  │  (Port 8086) │  │  (Port 8088) │  │              │
│ Auth + JWT   │  │ Redis + PG   │  │ Redis + PG   │  │  PostgreSQL  │
│ PostgreSQL   │  │ Heartbeats   │  │ Lua scripts  │  │  Call CRUD   │
└──────────────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
                         │                 │                  │
                    ┌────┴─────────────────┴──────────────────┴────┐
                    │                  Kafka                        │
                    │  Topics: call-events, agent-events,           │
                    │  routing-events, call-lifecycle-events,       │
                    │  user-events                                  │
                    └────┬─────────────────┬──────────────────┬────┘
                         │                 │                  │
                    ┌────▼─────┐    ┌──────▼──────┐   ┌──────▼──────┐
                    │ Audit    │    │ Analytics   │   │ Telephony   │
                    │ Service  │    │ Service     │   │ Service     │
                    │(Port 8091)│   │(Port 8090)  │   │(Port 8089)  │
                    │ PG only  │    │ Redis only  │   │ PG + Twilio │
                    └──────────┘    └─────────────┘   └─────────────┘
```

---

## The 9 Microservices

| # | Service                 | Port | One-Line Purpose                                                                                             |
|---|-------------------------|------|--------------------------------------------------------------------------------------------------------------|
| 1 | **API Gateway**         | 8080 | Single entry point — validates JWTs, extracts tenant ID, proxies requests to downstream services             |
| 2 | **User Service**        | 8081 | Manages user accounts, authentication (JWT issuance), roles (AGENT/SUPERVISOR), and multi-tenant isolation   |
| 3 | **Agent State Service** | 8086 | Tracks real-time agent status (AVAILABLE/BUSY/OFFLINE) in Redis + PG, detects disconnects via heartbeats    |
| 4 | **Call Service**        | 8087 | Manages call lifecycle (QUEUED → ROUTED → IN_PROGRESS → COMPLETED), persists records, handles recovery        |
| 5 | **Routing Service**     | 8088 | Matches calls to agents using skill-matching + Redis Lua scripts, retries with Fibonacci backoff             |
| 6 | **Telephony Service**   | 8089 | Integrates with Twilio for WebRTC voice — handles inbound calls, generates tokens, bridges calls to agents   |
| 7 | **WebSocket Gateway**   | 8085 | Pushes real-time Kafka events to the browser over STOMP WebSocket connections                                |
| 8 | **Analytics Service**   | 8090 | Aggregates real-time metrics (queue depth, agent counts, call stats) by consuming all Kafka topics           |
| 9 | **Audit Service**       | 8091 | Persists every system event to PostgreSQL as an immutable audit trail                                        |

---

## Tech Stack

| Layer               | Technology                                                           |
|---------------------|----------------------------------------------------------------------|
| **Frontend**        | Angular 17+, TypeScript, STOMP.js for WebSocket                      |
| **Backend**         | Java 17, Spring Boot 3, Spring Kafka, Spring WebSocket               |
| **Message Broker**  | Apache Kafka (5 topics)                                              |
| **Cache / State**   | Redis (agent state, skill sets, call queues, idempotency, heartbeat) |
| **Database**        | PostgreSQL (separate database per service)                           |
| **Telephony**       | Twilio Voice SDK (WebRTC), TwiML for call flow                       |
| **Auth**            | JWT (issued by user-service, validated by api-gateway)               |
| **Build**           | Gradle (multi-project), npm                                          |
| **Shared Code**     | `shared-common` module — DTOs, Kafka config, common models          |

---

## Communication Patterns

### 1. Synchronous (HTTP/REST)

Used for request-response flows where the caller needs an immediate answer.

| Caller | Callee | Why |
|---|---|---|
| Browser → API Gateway | All services | All external traffic goes through the gateway |
| User Service → Agent State Service | `POST /api/v1/agents/internal` | During agent creation, user-service provisions the agent profile via REST+X-Internal-Key |
| Telephony Service → Call Service | Create/start call | Twilio callbacks need immediate state updates |

### 2. Asynchronous (Kafka)

Used for event-driven communication where services react to state changes without blocking.

| Producer Class | Service | Topic | Consumer Classes | Consumer Services |
|---|---|---|---|---|
| `CallEventProducer.publishCallEvent()` | call-service | `call-events` | `KafkaMessaging`, `KafkaEventConsumer`, `AnalyticsEventConsumer`, `KafkaAuditConsumer` | routing-service, websocket-gateway, analytics-service, audit-service |
| `AgentEventProducer.publishAgentEvent()` | agent-state-service | `agent-events` | `AgentEventConsumer`, `KafkaEventConsumer`, `AnalyticsEventConsumer`, `KafkaAuditConsumer` | call-service, websocket-gateway, analytics-service, audit-service |
| `KafkaMessaging.publishRoutingResult()` | routing-service | `routing-events` | `RoutingEventConsumer`(×2), `RoutingEventConsumer`, `KafkaEventConsumer` | agent-state-service, call-service, telephony-service, websocket-gateway |
| `CallEventProducer.publishLifecycleEvent()` | call-service | `call-lifecycle-events` | `CallLifecycleConsumer`, `KafkaEventConsumer`, `AnalyticsEventConsumer`, `KafkaAuditConsumer` | agent-state-service, websocket-gateway, analytics-service, audit-service |
| `UserService` (inline) | user-service | `user-events` | `KafkaAuditConsumer` | audit-service |

### 3. WebSocket (STOMP over SockJS)

Used for pushing real-time updates from the backend to the browser.

| Direction | Channel | What Gets Pushed |
| Direction         | Channel                    | What Gets Pushed                                          |
|-------------------|----------------------------|-----------------------------------------------------------|
| Server → Browser  | `/topic/events/{tenantId}` | Agent state changes, call assignments, call completions   |

---

## Data Ownership

Each service owns its own PostgreSQL database. No service directly queries another service's database.

| Service | Database | Key Tables |
| Service              | Database                  | Key Tables                       |
|----------------------|---------------------------|----------------------------------|
| User Service         | `minigenesys_users`       | `users`, `user_skills`           |
| Agent State Service  | `minigenesys_agent_state` | `agents`, `agent_skills`         |
| Call Service         | `minigenesys_calls`       | `calls`                          |
| Routing Service      | `minigenesys_routing`     | `assignments`                    |
| Telephony Service    | `minigenesys_telephony`   | `telephony_call_sessions`        |
| Audit Service        | `minigenesys_audit`       | `audit_events`                   |
| Analytics Service    | — (Redis only)            | No PG tables — uses Redis        |

---

## Redis Key Lifecycle

Exact methods from `AgentStateService.updateRedisState()` and routing-service.

```
tenant:{id}:agent:{id}:state  (Hash)
  Created:  agent logs in  → opsForHash().put(stateKey, "status", "AVAILABLE")
  Updated:  every changeState() call
  Deleted:  agent goes OFFLINE → redisTemplate.delete(stateKey)

tenant:{id}:skill:{skill}:available  (Sorted Set — Score = lastAssignedTime)
  Added:    agent AVAILABLE → opsForZSet().add(skillKey, agentId, score)
  Removed:  agent BUSY or OFFLINE → opsForZSet().remove(skillKey, agentId)
  Never deleted (shared across all agents with that skill)

tenant:{id}:agent:{id}:heartbeat  (String, TTL=30s)
  Set/Reset: Angular setInterval every 15s → POST /heartbeat
             → AgentStateService.handleHeartbeat() → opsForValue().set(..., 30s TTL)
  Expires:  auto-deleted after 30s if browser closes
  Explicit delete: on logout

routing:lock:call:{callId}  (String, short TTL)
  Created:  before Lua script runs in routing-service (prevents race conditions)
  Released: after routing decision is published

routing:assignment:call:{callId}  (String)
  Created:  after successful ASSIGNED routing event
  Purpose:  idempotency — prevents Kafka re-delivery from double-assigning

tenant:{id}:call:queue  (Sorted Set)
  Added:    when no agent available → call enters retry queue
  Removed:  when RetryProcessor successfully routes the call
```

---

## Kafka Topics Summary

| Topic | Partition Key | Purpose |
|---|---|---|
| `call-events` | `tenantId` | New call created, call requeued after disconnect |
| `agent-events` | `tenantId` | Agent went AVAILABLE/BUSY/OFFLINE/DISCONNECTED |
| `routing-events` | `tenantId` | Call ASSIGNED to agent, NO_AGENT, ABANDONED |
| `call-lifecycle-events` | `tenantId` | Call COMPLETED (triggers agent release) |
| `user-events` | `tenantId` | User login/logout (for audit trail) |

---

## End-to-End Lifecycle (Annotated with Exact Class/Method Names)

```
1. AGENT LOGS IN
   Angular → POST /api/v1/agents/{id}/login
   → JwtAuthenticationFilter: validates JWT, headers.set("X-Tenant-Id", tenantId)
   → AgentStateController.login()
   → AgentStateService.changeState(tenantId, agentId, AVAILABLE)
     → agentRepository.save()  [PG: agents.status = AVAILABLE]
     → updateRedisState(agent, OFFLINE, AVAILABLE)
         opsForHash().put(stateKey, "status", "AVAILABLE")
         opsForZSet().add(skillKey, agentId, score)  ← enters routing pool
     → AgentEventProducer.publishAgentEvent(AGENT_AVAILABLE)
       → Kafka: agent-events
         → KafkaEventConsumer (ws-gateway): browser shows green "Ready"
         → AnalyticsEventConsumer: increments available count
         → KafkaAuditConsumer: audit_events record written

2. CALL CREATED
   Angular → POST /api/v1/calls
   → CallController.createCall()
   → CallService.createCall()
     → callRepository.save()  [PG: calls.status = QUEUED]
     → CallEventProducer.publishCallEvent()
       → Kafka: call-events
         → KafkaMessaging.consumeCallEvent() (routing-service)  ← NEXT

3. ROUTING ENGINE
   routing-service: KafkaMessaging.consumeCallEvent()
   → RoutingService.routeCall()
     → Executes Lua script on Redis:
         reads tenant:{id}:skill:{skill}:available (sorted set)
         atomically selects agent with lowest score
     → If agent found:
         → KafkaMessaging.publishRoutingResult(ASSIGNED)
           → Kafka: routing-events
             → RoutingEventConsumer (call-service): PG calls.status = ROUTED
             → RoutingEventConsumer (agent-state-service): agent → BUSY
             → RoutingEventConsumer (telephony-service): bridge Twilio call
             → KafkaEventConsumer (ws-gateway): browser shows "On Call"
     → If no agent:
         → add to Redis retry queue
         → publishRoutingResult(NO_AGENT_AVAILABLE)

4. AGENT GOES BUSY
   agent-state-service: RoutingEventConsumer.handleRoutingEvent()
   → AgentStateService.handleRoutingEvent()
     → agentRepository.save()  [PG: status=BUSY, activeCallId=callId]
     → updateRedisState(agent, AVAILABLE, BUSY)
         opsForHash().put(stateKey, "status", "BUSY")
         opsForZSet().remove(skillKey, agentId)  ← leaves routing pool
     → AgentEventProducer.publishAgentEvent(AGENT_BUSY)
       → Kafka: agent-events → ws-gateway → browser: "On Call" indicator

5. CALL COMPLETES
   Angular → POST /api/v1/calls/{callId}/complete
   → CallService.completeCall()
     → callRepository.save()  [PG: status=COMPLETED]
     → CallEventProducer.publishLifecycleEvent(CALL_COMPLETED)
       → Kafka: call-lifecycle-events
         → CallLifecycleConsumer (agent-state-service):
             AgentStateService.handleCallCompletion()
             → agentRepository.save()  [PG: status=AVAILABLE, activeCallId=null]
             → updateRedisState(agent, BUSY, AVAILABLE)
                 opsForZSet().add(skillKey, agentId, newScore)  ← re-enters pool
             → publishAgentEvent(AGENT_AVAILABLE)
         → KafkaEventConsumer (ws-gateway): browser clears call card after 3s
         → AnalyticsEventConsumer: updates metrics
         → KafkaAuditConsumer: audit record written

6. AGENT DISCONNECTS (browser crash)
   Heartbeat stops → Redis heartbeat key TTL auto-expires after 30s
   → AgentStateService.detectDisconnects() [@Scheduled every 10s]
     → PG query: agents WHERE status IN (AVAILABLE,BUSY) AND lastHeartbeatAt < threshold
     → For each expired agent:
         agentRepository.save()  [PG: status=OFFLINE]
         updateRedisState(agent, oldStatus, OFFLINE)
           opsForZSet().remove(skillKey, agentId)
           redisTemplate.delete(stateKey)
         publishAgentEvent(AGENT_DISCONNECTED)
           → Kafka: agent-events
             → AgentEventConsumer (call-service):
                 if agent had activeCallId → CallService requeues call
                 → CallEventProducer.publishCallEvent() → routing-service retries
```

---

## Multi-Tenancy

Every piece of data is scoped by `tenantId`:
- API Gateway's `JwtAuthenticationFilter` extracts `tenantId` from JWT and injects it as `X-Tenant-Id` header using `headers.set()` (overwrite, not append — prevents browser spoofing)
- Every PG query filters by `tenantId`
- Every Redis key is prefixed `tenant:{tenantId}:`
- Every Kafka message is partitioned by `tenantId`

---

## Key Design Decisions

| Decision | Rationale |
|---|---|
| **Redis as primary state for routing** | Decisions must be <200ms. PG is too slow for real-time selection. |
| **Kafka for inter-service communication** | Decouples services. Events are durable and replayable. |
| **Lua scripts for atomic selection** | Prevents race conditions during simultaneous assignments. |
| **Fibonacci backoff for retries** | Scales delays (1,1,2,3...) to avoid overwhelming the system. |
| **Heartbeat-based disconnect detection** | Detects crashes within 30s, triggers automatic call recovery. |
| **Idempotency cache for routing** | Ensures at-least-once Kafka delivery doesn't double-assign calls. |
| **Separate database per service** | Enforces strict boundaries, prevents accidental data coupling. |
| **`required=false` on X-Internal-Key** | Lets service return 401 (not Spring's default 400) on missing key. |
| **`anyExchange().permitAll()` in Gateway** | All auth delegated to `JwtAuthenticationFilter` GlobalFilter — Spring Security only disables CSRF. |
| **`headers.set()` not `headers.add()`** | Overwrites browser-injected X-Tenant-Id to prevent tenant spoofing. |

---

## How to Explain This in an Interview

> "I built a multi-tenant cloud contact center platform with 9 Spring Boot microservices. The API Gateway validates JWTs and injects tenant context as headers so no downstream service needs to parse tokens. When a customer call comes in, the call-service persists it and publishes to the `call-events` Kafka topic. The routing-service consumes that event and executes a Redis Lua script to atomically find the longest-idle available agent for the required skill. Once matched, the agent's state flips to BUSY across both Redis and PostgreSQL, and a WebSocket push from the websocket-gateway notifies the agent's browser in real-time. If no agent is available, the call enters a Fibonacci backoff retry queue. Disconnect recovery works via heartbeats — if an agent's browser crashes, their Redis heartbeat key expires after 30s, a `@Scheduled` job detects it, marks them OFFLINE, and publishes an `AGENT_DISCONNECTED` event that triggers the call-service to requeue the orphaned call."
