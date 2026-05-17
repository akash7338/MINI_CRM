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

### WebSocket Configuration (from `WebSocketConfig.java`)
```java
// Broker setup
config.enableSimpleBroker("/topic");        // in-memory broker, prefix for server-push destinations
config.setApplicationDestinationPrefixes("/app");  // prefix for client-to-server messages

// STOMP endpoint
registry.addEndpoint("/ws")               // browsers connect to: ws://host:8088/ws
    .setAllowedOriginPatterns("*")
    .withSockJS();                          // SockJS fallback for environments without native WS

// Channel interceptor (registered in configureClientInboundChannel)
registration.interceptors(authChannelInterceptor);  // AuthChannelInterceptor.preSend() runs on EVERY frame
```

### STOMP Frame Flow (exact `AuthChannelInterceptor.preSend()` logic)
```
1. CONNECT frame
   STOMP header: Authorization: Bearer <JWT>
   → AuthChannelInterceptor.preSend(message, channel)
       accessor.getCommand() == CONNECT
       authHeader = accessor.getFirstNativeHeader("Authorization")
       token = authHeader.substring(7)
       jwtUtil.validateToken(token)?
         if false → throw IllegalArgumentException("Unauthorized")  ← rejects connection
       claims = jwtUtil.getAllClaimsFromToken(token)
       tenantId = claims.get("tenantId", String.class)
       userId = claims.getSubject()
       auth = new UsernamePasswordAuthenticationToken(userId, null, emptyList())
       accessor.getSessionAttributes().put("tenantId", tenantId)  ← stored for SUBSCRIBE check
       accessor.setUser(auth)
       → return message (connection proceeds)

2. SUBSCRIBE frame
   Destination: /topic/events/tenant1
   → AuthChannelInterceptor.preSend(message, channel)
       accessor.getCommand() == SUBSCRIBE
       destination = accessor.getDestination()   // "/topic/events/tenant1"
       tenantId = accessor.getSessionAttributes().get("tenantId")  // from CONNECT step
       requestedTenantId = destination.substring("/topic/events/".length())  // "tenant1"
       if tenantId == null || requestedTenantId != tenantId:
         throw IllegalArgumentException("Forbidden")  ← cross-tenant blocked
       → return message (subscription proceeds)

3. MESSAGE frames (server → client, pushed by KafkaEventConsumer)
   messagingTemplate.convertAndSend("/topic/events/tenant1", RealtimeEvent)
   Destination: /topic/events/tenant1
   Body (JSON): { "topic": "agent-events", "payload": {...}, "receivedAt": "2026-05-17T..." }
   → SimpleBroker fans out to all subscribers on /topic/events/tenant1
   → All browsers for that tenant receive the event simultaneously
```

---

## 5. Kafka Usage

### Consumes ← 4 Topics (single method, `@Header` injection)

**Single consumer method handles all 4 topics:**
```java
@KafkaListener(
    topics = {"call-events", "routing-events", "agent-events", "call-lifecycle-events"},
    groupId = "websocket-gateway-group"
)
public void consume(
    String message,
    @Header(KafkaHeaders.RECEIVED_TOPIC) String topic  // which topic this message came from
)
```

| Topic | What It Contains |
|---|---|
| `call-events` | New calls created, calls requeued |
| `routing-events` | Call assigned to agent, no agent, abandoned |
| `agent-events` | Agent went available, busy, offline, disconnected |
| `call-lifecycle-events` | Call completed |

**Processing logic inside `consume()`:**
1. `objectMapper.readTree(message)` — parses JSON into `JsonNode`
2. `node.get("tenantId").asText()` — extracts tenant from payload
3. Wraps in `RealtimeEvent.builder().topic(topic).payload(node).receivedAt(Instant.now()).build()`
4. `messagingTemplate.convertAndSend("/topic/events/" + tenantId, event)`
5. If `tenantId == null` or blank — **silently drops the event** (no send)
6. If JSON parse fails — sets `payload = raw message string`, still pushes if tenantId found
7. On any exception — throws `RuntimeException` to trigger Kafka DLQ

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

> "The WebSocket Gateway is the real-time event delivery layer. It maintains persistent STOMP-over-SockJS WebSocket connections with all connected browsers. It consumes from four Kafka topics — call-events, routing-events, agent-events, and call-lifecycle-events — using a **single `consume()` method** that uses Spring's `@Header(KafkaHeaders.RECEIVED_TOPIC)` annotation to know which topic a message came from. It pushes every event to the browser in real-time via Spring's `SimpMessagingTemplate`. Authentication happens on the STOMP CONNECT frame — the `AuthChannelInterceptor.preSend()` validates the JWT and extracts the tenantId into session attributes. On SUBSCRIBE, it enforces tenant isolation by verifying the subscription destination matches the authenticated tenant — so tenant A can never see tenant B's events. The frontend's `SessionStateService` then routes each event based on its `topic` field to update status badges, show/hide the call panel, and maintain the activity log. This eliminates the need for HTTP polling and gives us sub-200ms UI updates."

### Key Technical Details Worth Mentioning

The gateway uses **one unified channel per tenant** (`/topic/events/{tenantId}`) rather than separate channels per event type. This simplifies the subscription model — the browser subscribes once and the frontend handles filtering. The trade-off is that every browser receives every event for their tenant (including events for other agents), but since the frontend filters by `agentId`, this is harmless and keeps the architecture simpler.

**Scaling limitation:** The current `SimpleBroker` is **in-memory only**. In a multi-instance deployment, each gateway instance would only know about its own WebSocket connections. If instance A handles a Kafka message, it can only push to browsers connected to instance A. To fix this in production, replace `enableSimpleBroker("/topic")` with `enableStompBrokerRelay()` backed by a Redis or RabbitMQ broker.

---

## 12. Annotated Flow Traces (Exact Methods)

### Angular WebSocket Connection Lifecycle
```
Angular app loads:
→ SessionStateService constructor calls WebsocketService.connect()
→ SockJS("http://localhost:8088/ws")
→ STOMP CONNECT frame:
    Authorization: Bearer eyJhbGci...
    → AuthChannelInterceptor.preSend()
        command == CONNECT
        token = header.substring(7)
        jwtUtil.validateToken(token)  ← shared JwtUtil from shared-common
        if valid:
          tenantId = claims.get("tenantId")
          sessionAttributes.put("tenantId", tenantId)
          accessor.setUser(UsernamePasswordAuthenticationToken)
          connection proceeds
        if invalid:
          throw IllegalArgumentException("Unauthorized")  ← connection rejected

→ STOMP SUBSCRIBE frame:
    Destination: /topic/events/tenant1
    → AuthChannelInterceptor.preSend()
        command == SUBSCRIBE
        requestedTenantId = "tenant1"  (parsed from destination)
        sessionTenantId = sessionAttributes.get("tenantId")  // set at CONNECT
        if mismatch: throw IllegalArgumentException("Forbidden")
        subscription proceeds
        Browser now receives all events for tenant1
```

### Kafka Event → Browser Push
```
Kafka: agent-events message (JSON string)
{
  "eventType": "AGENT_BUSY",
  "agentId": "AG_001",
  "tenantId": "tenant1",
  "newStatus": "BUSY",
  ...
}

→ KafkaEventConsumer.consume(message, topic="agent-events")
    → node = objectMapper.readTree(message)
    → tenantId = node.get("tenantId").asText()  // "tenant1"
    → if tenantId blank: drop silently, return
    → event = RealtimeEvent {
          topic: "agent-events",
          payload: node,                    ← full JSON object
          receivedAt: Instant.now()
      }
    → messagingTemplate.convertAndSend("/topic/events/tenant1", event)
        → SimpleBroker fans out to all subscribers on /topic/events/tenant1
        → Every browser tab for tenant1 receives:
            { "topic": "agent-events", "payload": {...}, "receivedAt": "..." }

Angular SessionStateService.handleEvent(event):
  switch(event.topic):
    "agent-events":
      if event.payload.agentId == myAgentId:
        update status badge to event.payload.newStatus
    "routing-events":
      if event.payload.agentId == myAgentId && status == "ASSIGNED":
        show call panel, set status to "On Call"
    "call-lifecycle-events":
      if event.payload.agentId == myAgentId && eventType == "CALL_COMPLETED":
        setTimeout(() => clearCallPanel(), 3000)
```

