# WebSocket Gateway

## 1. One-Line Purpose

A real-time event bridge — consumes all Kafka events and pushes them to the agent's browser over STOMP WebSocket connections, enabling live dashboard updates without polling.

---

## 2. When This Service Comes Into Picture

The WebSocket Gateway is involved **constantly** during an active session:

1. **Browser loads** — Angular app opens a STOMP WebSocket connection to `/ws` with the JWT in the CONNECT frame
2. **Any Kafka event fires** — Agent state changes, call assignments, call completions — all get pushed to the browser in real-time
3. **Dashboard shows live updates** — Status badges, call panels, activity logs all update instantly without HTTP polling

The gateway is the **last mile** of every event's journey: it's the final hop from Kafka → browser.

---

## 3. Responsibilities

1. **WebSocket Connection Management** — Maintains persistent STOMP-over-SockJS connections with all connected browsers
2. **JWT Authentication on CONNECT** — Validates the JWT when the WebSocket connection is first established
3. **Tenant Isolation on SUBSCRIBE** — Ensures users can only subscribe to their own tenant's event channel
4. **Kafka Fan-Out** — Consumes events from 4 Kafka topics and broadcasts them to the correct tenant channel
5. **Event Wrapping** — Wraps raw Kafka payloads in a `RealtimeEvent` envelope with `topic` and `receivedAt` metadata

---

## 4. APIs Exposed

### WebSocket Endpoint
| Endpoint | Protocol | Purpose |
|---|---|---|
| `/ws` | STOMP over SockJS | Main WebSocket connection endpoint |

### STOMP Channels
| Channel | Direction | Subscribers |
|---|---|---|
| `/topic/events/{tenantId}` | Server → Client | All browsers connected for that tenant |

The gateway has **no REST endpoints** (besides health). All communication is via WebSocket push.

### STOMP Frame Flow
```
1. CONNECT frame
   Header: Authorization: Bearer <JWT>
   → AuthChannelInterceptor validates JWT
   → Extracts tenantId, stores in session attributes
   → Sets Spring Security principal

2. SUBSCRIBE frame
   Destination: /topic/events/tenant1
   → AuthChannelInterceptor checks tenantId matches session
   → Blocks cross-tenant subscriptions

3. MESSAGE frames (server → client)
   Destination: /topic/events/tenant1
   Body: { topic, payload, receivedAt }
   → Pushed by KafkaEventConsumer via SimpMessagingTemplate
```

---

## 5. Kafka Usage

### Consumes ← 4 Topics

| Topic | What It Contains |
|---|---|
| `call-events` | New calls created, calls requeued |
| `routing-events` | Call assigned to agent, no agent, abandoned |
| `agent-events` | Agent went available, busy, offline, disconnected |
| `call-lifecycle-events` | Call completed |

**Consumer Group:** `websocket-gateway-group`

**Processing Logic (`KafkaEventConsumer.consume()`):**
1. Parse JSON payload
2. Extract `tenantId` from the JSON
3. Wrap in `RealtimeEvent` object: `{ topic, payload, receivedAt }`
4. Send to `/topic/events/{tenantId}` via `SimpMessagingTemplate.convertAndSend()`

All events for a tenant go to the **same channel**. The frontend's `SessionStateService.handleEvent()` uses the `topic` field to determine how to handle each event type.

---

## 6. Redis Usage

**None.** The gateway uses Spring's built-in `SimpleBroker` for WebSocket message routing. In a production multi-instance deployment, you would replace this with a Redis-backed broker for horizontal scaling, but the current implementation uses the in-memory simple broker.

---

## 7. PostgreSQL Usage

**None.** The gateway is completely stateless — it has no database. It just pipes events from Kafka to WebSocket.

---

## 8. Important State Changes

The gateway itself is stateless, but it plays a critical role in how the **frontend** handles state:

### Frontend Event Routing (`SessionStateService.handleEvent()`)

| Incoming Topic | Frontend Behavior |
|---|---|
| `agent-events` with matching agentId | Updates the status badge (Ready / On Call / Offline) |
| `agent-events` with `OFFLINE` but active call exists | **Ignored** — prevents flickering during heartbeat race conditions |
| `routing-events` with `ASSIGNED` and matching agentId | Shows the call panel, sets status to "On Call" |
| `call-lifecycle-events` with `CALL_COMPLETED` | Clears the call panel after 3-second delay, sets status to "Ready" |

---

## 9. Interaction With Other Services

| Direction | Service | How | Why |
|---|---|---|---|
| **Consumes ←** | All producing services | Kafka (4 topics) | Receives every event in the system |
| **Pushes →** | Angular dashboard | STOMP WebSocket | Real-time browser updates |
| **Uses** | `shared-common` (JwtUtil) | Compile-time dependency | JWT validation on WebSocket CONNECT |

### Why Not Direct REST Polling?
The alternative would be the browser polling `GET /agents/AG_001/state` every second. For 1000 concurrent agents, that's 1000 requests/second just for status updates. WebSocket push eliminates this entirely — the server only sends data when something actually changes.

---

## 10. Edge Cases / Failure Scenarios

| Scenario | What Happens |
|---|---|
| **Invalid JWT on CONNECT** | `AuthChannelInterceptor` throws `IllegalArgumentException("Unauthorized")` → WebSocket connection rejected |
| **Missing Authorization header** | Same as above — connection rejected |
| **User tries to subscribe to another tenant's channel** | `AuthChannelInterceptor` compares the subscription destination's tenantId with the session's tenantId. Mismatch → `IllegalArgumentException("Forbidden")` |
| **Kafka consumer lag (events delayed)** | Browser shows stale state. No retry mechanism — events will arrive when the consumer catches up |
| **Browser disconnects (tab closed)** | Spring automatically cleans up the WebSocket session. No explicit handling needed |
| **F5 refresh** | Old WebSocket connection dies. Angular re-establishes a new one via `WebsocketService.connect()` in `SessionStateService` constructor. There may be a few seconds of missed events during reconnection |
| **Gateway crashes and restarts** | All WebSocket connections drop. Browsers will auto-reconnect (SockJS has built-in reconnection). Kafka consumer resumes from the committed offset |
| **Malformed JSON in Kafka message** | `objectMapper.readTree()` fails → payload is set to the raw string → event is still pushed but frontend may not understand it |

### Multi-Tenancy Security
```java
// On SUBSCRIBE, the interceptor enforces:
String requestedTenantId = destination.substring("/topic/events/".length());
if (!requestedTenantId.equals(sessionTenantId)) {
    throw new IllegalArgumentException("Forbidden");
}
```
This prevents any user from subscribing to another tenant's event stream, even with a valid JWT.

---

## 11. Interview Explanation

> "The WebSocket Gateway is the real-time event delivery layer. It maintains persistent STOMP-over-SockJS WebSocket connections with all connected browsers. It consumes from four Kafka topics — call-events, routing-events, agent-events, and call-lifecycle-events — and pushes every event to the browser in real-time via Spring's `SimpMessagingTemplate`. Authentication happens on the STOMP CONNECT frame — the `AuthChannelInterceptor` validates the JWT and extracts the tenantId into session attributes. On SUBSCRIBE, it enforces tenant isolation by verifying the subscription destination matches the authenticated tenant — so tenant A can never see tenant B's events. The frontend's `SessionStateService` then routes each event based on its topic field to update status badges, show/hide the call panel, and maintain the activity log. This eliminates the need for HTTP polling and gives us sub-200ms UI updates."

### Key Technical Detail Worth Mentioning

The gateway uses **one unified channel per tenant** (`/topic/events/{tenantId}`) rather than separate channels per event type. This simplifies the subscription model — the browser subscribes once and the frontend handles filtering. The trade-off is that every browser receives every event for their tenant (including events for other agents), but since the frontend filters by `agentId`, this is harmless and keeps the architecture simpler.
