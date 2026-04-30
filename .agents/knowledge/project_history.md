# Mini Genesys — Project History

## Project Overview
Mini Genesys is a multi-tenant cloud contact center platform designed to provide real-time call routing, agent state management, and analytics. It aims to replicate core Genesys-like functionality (Routing, ACD, Presence, Telephony) in a modern, microservices-based architecture.

**Core Use Cases:**
- **Inbound Call Handling**: Routing PSTN/WebRTC calls to the most suitable agent.
- **Agent Presence**: Real-time tracking of agent availability (Available, Busy, Offline).
- **Skill-Based Routing**: Matching call requirements with agent skills using a Least Recently Used (LRU) algorithm.
- **Real-Time Monitoring**: Providing supervisors and agents with live updates via WebSockets.

---

## Architecture & Tech Stack
The system is built as a distributed microservices platform.

- **Backend**: Java 21, Spring Boot 3.x, Spring Cloud Gateway.
- **Frontend**: Angular 17+, TypeScript.
- **Messaging**: Apache Kafka (Event-driven communication).
- **In-Memory Store**: Redis (Real-time agent state, sorted sets for routing, Pub/Sub for WebSockets).
- **Database**: PostgreSQL (Persistent storage for calls, agents, users, and audit logs).
- **Telephony**: Twilio (Voice SDK, TwiML for call control).
- **Observability**: Jaeger (Tracing), Prometheus/Grafana (Metrics - planned).

### System Design
1.  **API Gateway (Port 8080)**: Entry point, JWT validation, tenant extraction.
2.  **User Service (Port 8090)**: Manages users, tenants, and authentication.
3.  **Agent State Service (Port 8086)**: Maintains real-time status and heartbeats.
4.  **Call Service (Port 8087)**: Manages call lifecycle and persistence.
5.  **Routing Service (Port 8085)**: Implements the ACD (Automatic Call Distributor) logic.
6.  **Telephony Service (Port 8092)**: Interfaces with Twilio for call signaling and bridging.
7.  **Analytics Service (Port 8089)**: Aggregates metrics from Kafka events.
8.  **WebSocket Gateway (Port 8088)**: Bridges Kafka/Redis events to connected browser clients.
9.  **Audit Service**: Persistent record of all system events.

---

## Key Components

### Routing Engine (ACD)
- Uses Redis **Sorted Sets** to track available agents per skill.
- **Score**: `lastAssignedTime` (epoch ms).
- **Algorithm**: Intersects sets of required skills and selects the agent with the lowest score (LRU).
- **Fallback**: Fibonacci backoff for re-queuing calls when no agents are available.

### Telephony Bridging (Polling Pattern)
- Twilio calls are kept in a "wait-loop" using TwiML `<Redirect>` to a `/bridge` endpoint.
- The `/bridge` endpoint checks the database for an `assignedAgentId`.
- Once assigned, it returns `<Dial><Client>agentId</Client></Dial>`.

### Session State Management (Frontend)
- `SessionStateService` manages the global state of the agent and active calls.
- Restores state on browser refresh by querying backend services using stored `agentId`.

---

## Current Progress

### Fully Implemented
- Core microservices skeleton and inter-service communication via Kafka.
- JWT-based authentication and multi-tenant routing in API Gateway.
- Agent login/logout and status transitions (Available, Busy, Offline).
- Basic skill-based routing (LRU).
- Twilio inbound call handling and TwiML wait-loops.
- Real-time event propagation to the Angular dashboard.

### Partially Done / In Progress
- **Telephony Routing Persistence**: Currently stabilizing state consistency to prevent "Ghost Calls" where a call remains active in the UI but is disconnected in the backend.
- **Heartbeat Mechanism**: Implemented but needs tuning for faster disconnect detection.
- **Analytics Aggregation**: Basic metrics are recorded; complex SLA calculations are pending.

### Not Started
- Supervisor "Barge-in" or "Listen-in" features.
- Multi-region Kafka/Redis replication.
- Advanced reporting dashboards.

---

## Important Decisions

1.  **Eventual Consistency vs. Real-time**: Chosen Kafka for most state updates to ensure horizontal scalability, while using Redis for the "hot path" of routing to keep latency <200ms.
2.  **TwiML Bridging Pattern**: Decided against complex SIP/Media Server orchestration in early phases, opting for Twilio's HTTP-based call control for simplicity.
3.  **Cyclic Dependency Refactor**: Refactored `AgentStateService` to decouple Kafka producers from consumers using specialized components (`AgentEventProducer`, `RoutingEventConsumer`), ensuring a clean DAG dependency structure.
4.  **Tenant Isolation**: All data and messaging keys are prefixed with `tenantId` (e.g., `tenant:{id}:agent:{id}:state`).

---

## Workflows & Processes

### Build & Run
- **Requirements**: Docker, Java 21, Node.js.
- **Backend**: Each service is a Gradle project. Run via `./gradlew bootRun`.
- **Infrastructure**: Start Kafka, Redis, and Postgres via Docker (refer to `docker-compose.yml` if available).
- **Frontend**: Run `npm start` in `minigenesys-dashboard`.

### Data Flow
1.  **Call Inbound**: Twilio -> Telephony Svc -> Call Svc -> Kafka (`call-events`).
2.  **Routing**: Routing Svc (consumes `call-events`) -> Match Agent -> Update Redis/Postgres -> Kafka (`routing-events`).
3.  **Notification**: WebSocket GW (consumes `routing-events`) -> Push to Browser.
4.  **Bridging**: Browser Agent receives event -> Connects to Twilio Voice -> Telephony Svc bridges audio.

---

## Known Issues / TODOs
- **Ghost Calls**: Inconsistencies between UI and Backend state during rapid refreshes or network blips.
- **409 Conflict Errors**: Occur during agent identity synchronization if multiple "heartbeats" or state updates collide.
- **Redis TTLs**: Need to ensure all transient routing keys have appropriate TTLs to prevent memory leaks.
- **Test Coverage**: Unit tests are present in most services, but integration tests for the full Twilio loop are simulated.

---

## Assumptions & Context
- **Internal Only APIs**: Some APIs (like routing assignment) are designed for internal use and blocked at the Gateway.
- **Simulated Environment**: While Twilio integration is "real", many tests use simulated `CallSid` values to avoid costs.
- **Agent Identity**: The system assumes the `agentId` in the dashboard matches the `userId` in the `user-service`.
