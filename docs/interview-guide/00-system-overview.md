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
       ▼                                  │
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

| # | Service | Port | One-Line Purpose |
|---|---------|------|------------------|
| 1 | **API Gateway** | 8080 | Single entry point — validates JWTs, extracts tenant ID, proxies requests to downstream services |
| 2 | **User Service** | 8081 | Manages user accounts, authentication (JWT issuance), roles (AGENT/SUPERVISOR), and multi-tenant isolation |
| 3 | **Agent State Service** | 8086 | Tracks real-time agent status (AVAILABLE/BUSY/OFFLINE) in Redis + PostgreSQL, detects disconnects via heartbeats |
| 4 | **Call Service** | 8087 | Manages the call lifecycle (QUEUED → ROUTED → IN_PROGRESS → COMPLETED), persists call records, handles agent disconnect recovery |
| 5 | **Routing Service** | 8088 | Matches incoming calls to the best available agent using skill-based routing with Redis Lua scripts, retries with Fibonacci backoff |
| 6 | **Telephony Service** | 8089 | Integrates with Twilio for real WebRTC voice — handles inbound calls, generates tokens, bridges calls to agents |
| 7 | **WebSocket Gateway** | 8085 | Pushes real-time Kafka events to the browser over STOMP WebSocket connections |
| 8 | **Analytics Service** | 8090 | Aggregates real-time metrics (queue depth, agent counts, call stats) by consuming all Kafka topics |
| 9 | **Audit Service** | 8091 | Persists every system event to PostgreSQL as an immutable audit trail |

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Frontend** | Angular 17+, TypeScript, STOMP.js for WebSocket |
| **Backend** | Java 17, Spring Boot 3, Spring Kafka, Spring WebSocket |
| **Message Broker** | Apache Kafka (5 topics) |
| **Cache / State** | Redis (agent state, skill sets, call queues, idempotency, heartbeats) |
| **Database** | PostgreSQL (separate database per service) |
| **Telephony** | Twilio Voice SDK (WebRTC), TwiML for call flow |
| **Auth** | JWT (issued by user-service, validated by api-gateway) |
| **Build** | Gradle (multi-project), npm |
| **Shared Code** | `shared-common` module — DTOs, Kafka config, common models |

---

## Communication Patterns

### 1. Synchronous (HTTP/REST)

Used for request-response flows where the caller needs an immediate answer.

| Caller | Callee | Why |
|--------|--------|-----|
| Browser → API Gateway | All services | All external traffic goes through the gateway |
| Telephony Service → Call Service | Create/start/complete calls | Twilio callbacks need immediate call state updates |

### 2. Asynchronous (Kafka)

Used for event-driven communication where services react to state changes without blocking.

| Producer | Topic | Consumers |
|----------|-------|-----------|
| Call Service | `call-events` | Routing Service, WebSocket Gateway, Analytics, Audit |
| Agent State Service | `agent-events` | Call Service (recovery), WebSocket Gateway, Analytics, Audit |
| Routing Service | `routing-events` | Agent State Service, Call Service, Telephony Service, WebSocket Gateway, Analytics, Audit |
| Call Service | `call-lifecycle-events` | Agent State Service, WebSocket Gateway, Analytics, Audit |
| User Service | `user-events` | Audit Service |

### 3. WebSocket (STOMP over SockJS)

Used for pushing real-time updates from the backend to the browser.

| Direction | Channel | What Gets Pushed |
|-----------|---------|-----------------|
| Server → Browser | `/topic/events/{tenantId}` | Agent state changes, call assignments, call completions |

---

## Data Ownership

Each service owns its own PostgreSQL database. No service directly queries another service's database.

| Service | Database | Key Tables |
|---------|----------|------------|
| User Service | `minigenesys_users` | `users`, `user_skills` |
| Agent State Service | `minigenesys_agent_state` | `agents`, `agent_skills` |
| Call Service | `minigenesys_calls` | `calls` |
| Routing Service | `minigenesys_routing` | `assignments` |
| Telephony Service | `minigenesys_telephony` | `telephony_call_sessions` |
| Audit Service | `minigenesys_audit` | `audit_events` |
| Analytics Service | — (Redis only) | No PostgreSQL tables — uses Redis counters |

---

## Redis Usage Summary

Redis serves as the **hot state layer** — the fast, in-memory source of truth for real-time decisions.

| Key Pattern | Type | Owner Service | Purpose |
|-------------|------|---------------|---------|
| `tenant:{id}:agent:{id}:state` | Hash | Agent State Service | Agent's current status + last assigned time |
| `tenant:{id}:skill:{skill}:available` | Sorted Set | Agent State Service | Available agents per skill, scored by LRU |
| `tenant:{id}:agent:{id}:heartbeat` | String | Agent State Service | Heartbeat timestamp, TTL = 30s |
| `tenant:{id}:call:queue` | Sorted Set | Routing Service (QueueManager) | Calls waiting for agents, scored by priority |
| `routing:lock:call:{id}` | String | Routing Service | Distributed lock to prevent duplicate routing |
| `routing:assignment:call:{id}` | String | Routing Service | Idempotency cache — remembers which agent was assigned |
| `routing:retry:{tenant}:{call}:*` | Multiple | Routing Service (RetryProcessor) | Retry count, last retry time, call data |
| `analytics:{tenant}:*` | Strings | Analytics Service | Real-time counters (queue depth, agent counts) |

---

## Kafka Topics Summary

| Topic | Partition Key | Purpose |
|-------|--------------|---------|
| `call-events` | `tenantId` | New call created, call requeued after agent disconnect |
| `agent-events` | `tenantId` | Agent went AVAILABLE/BUSY/OFFLINE/DISCONNECTED |
| `routing-events` | `tenantId` | Call ASSIGNED to agent, NO_AGENT, ABANDONED |
| `call-lifecycle-events` | `tenantId` | Call COMPLETED (triggers agent release) |
| `user-events` | `tenantId` | User login/logout (for audit trail) |

---

## Multi-Tenancy

Every piece of data in the system is scoped by `tenantId`. This means:
- Every API request carries a tenant ID (extracted from the JWT by the API Gateway)
- Every database query is filtered by tenant ID
- Every Redis key is prefixed with `tenant:{tenantId}:`
- Every Kafka message is partitioned by `tenantId`
- No tenant can ever see another tenant's agents, calls, or data

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Redis as primary state for routing** | Routing decisions must be < 200ms. PostgreSQL is too slow for real-time agent selection across thousands of agents |
| **Kafka for inter-service communication** | Decouples services — the call-service doesn't need to know about the routing-service. Events are durable and replayable |
| **Lua scripts for atomic agent selection** | The routing engine must atomically find + claim an agent. Redis Lua scripts run as a single atomic operation, preventing race conditions |
| **Fibonacci backoff for retries** | When no agent is available, the system retries with increasing delays (1s, 1s, 2s, 3s, 5s, 8s, 13s...) to avoid overwhelming Redis |
| **Heartbeat-based disconnect detection** | Agents send a ping every 15s. If the server doesn't hear from them for 30s, it assumes they crashed and triggers call recovery |
| **Idempotency cache for routing** | Kafka delivers messages "at least once" — the idempotency cache ensures the same call isn't assigned to two different agents |
| **Separate database per service** | Enforces service boundaries. No service can accidentally couple to another's data model |

---

## How to Explain This in an Interview

> "I built a multi-tenant cloud contact center platform with 9 Spring Boot microservices. When a customer call comes in, it hits the call-service which persists it and publishes an event to Kafka. The routing-service consumes that event and uses a Redis Lua script to atomically find the best available agent based on skill-matching and least-recently-used scheduling. Once matched, the agent's state flips to BUSY in Redis and PostgreSQL, and a WebSocket push notifies the agent's browser in real-time. If no agent is available, the call enters a retry queue with Fibonacci backoff. The system handles agent crashes through heartbeat-based disconnect detection — if an agent's browser dies, their active call is automatically requeued to another agent within 30 seconds. Everything is multi-tenant isolated, event-driven, and uses Redis as the hot state layer for sub-200ms routing decisions."
