# Issues and Fixes Summary

---

## 1. Ghost Call Re-assignment Loop

- **Problem:** After an F5 browser refresh, the agent's status flickered between OFFLINE and BUSY indefinitely, even though they weren't on any call.
- **Root Cause:** A three-service race condition. The heartbeat timeout marked the agent OFFLINE → `CallService` requeued the active call → `RoutingEngine` hit its idempotency cache, which still held the same agent ID, and blindly re-assigned the call → `AgentStateService` accepted the ASSIGNED event and flipped the agent back to BUSY → cycle repeated every ~10 seconds.
- **Impact:** Infinite assignment loop; agent unusable; Kafka flooded with repeating events.
- **Fix:**
  - `RoutingEngine.assignAgent()` — Before trusting the idempotency cache, verify the agent's current status in Redis. If OFFLINE, invalidate the cache entry and re-route.
  - `AgentStateService.handleRoutingEvent()` — Reject any ASSIGNED event if the agent is currently OFFLINE (defense in depth).
  - Frontend `agent-panel.component.ts` — Disabled the "End Shift" button when agent status is "On Call" to prevent illegal state transitions.
- **Key Learning:** Every cache in a distributed system must be validated against the source of truth before being trusted.

---

## 2. WebSocket Gateway Connection Refused (500 on `/ws/info`)

- **Problem:** API Gateway returned `500 Server Error` for `GET /ws/info` with `Connection refused: localhost/127.0.0.1:8088`.
- **Root Cause:** The WebSocket Gateway service (port 8088) was not running when the browser tried to establish a WebSocket connection through the API Gateway.
- **Impact:** No real-time event delivery to the frontend; dashboard showed stale data.
- **Fix:** Restart the WebSocket Gateway service.
- **Key Learning:** The API Gateway proxies `/ws/**` to port 8088 — if that downstream service is down, the gateway surfaces a 500 instead of a clean error.

---

## 3. WebSocket Unauthorized Token Infinite Retry Loop

- **Problem:** After the WebSocket Gateway came back up, the logs showed `Invalid WebSocket token` / `WebSocket auth error: Unauthorized` repeating every ~6 seconds for several minutes.
- **Root Cause:** The frontend's SockJS client had a stale/expired JWT token. On each rejection, SockJS automatically reconnected with the same expired token, creating an infinite loop.
- **Impact:** Hundreds of failed connection attempts flooding the server logs; no WebSocket connection established until a fresh login occurred.
- **Fix:** Updated `websocket.service.ts` — added an `onStompError` handler that detects `Unauthorized` in the STOMP error frame, clears `localStorage`, and hard-redirects to `/login`.
- **Key Learning:** Auto-reconnect libraries (SockJS/STOMP) need explicit handling for auth failures to avoid hammering the server with expired credentials.

---

## 4. Telephony Race Condition — Call Stuck "In Queue"

- **Problem:** After calling the Twilio number, the caller heard "Your call is still in queue" indefinitely, even though the logs confirmed an agent was successfully assigned within 89ms.
- **Root Cause:** A race condition between two threads inside the `telephony-service`:
  - **Thread A (Webhook):** `handleInboundCall()` called `callServiceClient.createInternalCall()` (line 52), which triggered the entire Kafka routing chain.
  - **Thread B (Kafka Consumer):** The routing chain completed so fast that `handleAssignment()` (line 80) tried to `findByInternalCallId()` in PostgreSQL **before** Thread A had finished executing `saveNewSession()` (line 55).
  - Result: Thread B found nothing in the DB, silently skipped the update, and `assignedAgentId` remained `NULL` forever.
- **Impact:** Twilio's `/bridge` endpoint saw `assignedAgentId = null` and kept returning "still in queue" TwiML. The call never connected.
- **Fix:** Changed `handleAssignment()` from `ifPresent()` (silent skip) to `.orElseThrow()` (throws `RuntimeException`). This triggers Kafka's `DefaultErrorHandler` with exponential backoff retry. On retry (~1 second later), Thread A has finished saving the session, and Thread B successfully updates the `assignedAgentId`.
- **Key Learning:** In event-driven architectures, an asynchronous side-effect (Kafka chain) can outrun the synchronous thread that triggered it — use retriable exceptions as a self-healing mechanism.

---

## 5. `callerId` (Phone Number) Not Persisted in Call Service

- **Problem:** The `telephony-service` sent the caller's phone number (`fromNumber`) as `callerId` in the REST body to `call-service`, but `call-service` completely ignored it.
- **Root Cause:** The `CreateCallRequest` DTO, `Call` entity, and `CallResponse` DTO in `call-service` had no `callerId` field. The field was silently dropped by Jackson during deserialization.
- **Impact:** No record of who actually called. The `calls` table had no trace of the caller's phone number.
- **Fix:**
  - Added `callerId` field to `CreateCallRequest` DTO.
  - Added `callerId` column to `Call` JPA entity (nullable, so existing records are unaffected with `ddl-auto: update`).
  - Added `callerId` to `CallResponse` DTO and `mapToResponse()` builder.
- **Key Learning:** When integrating services, verify end-to-end that every field sent is actually consumed and persisted — silent deserialization drops are easy to miss.

---

## 6. Type Safety Warning & Inline Imports in `CallServiceClient`

- **Problem:** Java compiler warning: `Type safety: The expression of type Map needs unchecked conversion to conform to Map<String,Object>`. Additionally, all Spring HTTP classes were used with fully-qualified names (e.g., `org.springframework.http.HttpHeaders`) instead of imports.
- **Root Cause:** `RestTemplate.postForObject(..., Map.class)` returns a raw `Map` (no generics at runtime due to type erasure). Assigning it to `Map<String, Object>` triggers an unchecked conversion warning. The inline FQN usage was just code style debt.
- **Impact:** Compiler warnings; reduced code readability.
- **Fix:**
  - Added `@SuppressWarnings("unchecked")` above the `restTemplate.postForObject()` call.
  - Added proper `import` statements for `HttpHeaders`, `HttpEntity`, `MediaType` and replaced all inline FQN references.
- **Key Learning:** `RestTemplate` with `Map.class` always produces unchecked warnings — `@SuppressWarnings` is the standard, accepted fix in Spring applications.

---

## 7. Analytics Data Inflation (1748 Total Calls)

- **Problem:** The `MetricsPanelComponent` on the frontend dashboard mysteriously showed exactly 1748 total calls and 36 completed calls. 
- **Root Cause:** A combination of a serialization bug and a previous "Ghost Call" loop. `CallEvent` used `@Builder.Default private boolean isNew = true;`. Lombok generated the getter `isNew()`, which Jackson serialized as `"new": true`. However, `AnalyticsEventConsumer` was checking `!node.has("isNew")`. Since the field in the JSON was named `"new"`, it evaluated to `true` for every single message on the `call-events` topic. During prior Ghost Call debugging, an agent disconnect loop continuously requeued calls, flooding Kafka with `call-events` messages that Analytics incorrectly counted as brand new calls.
- **Impact:** `totalCalls` was artificially inflated by thousands of events.
- **Fix:** Renamed the field in `CallEvent.java` from `isNew` to `newCall` so Jackson serializes it predictably as `"newCall"`. Updated `AnalyticsEventConsumer` to check for `"newCall"`.
- **Key Learning:** Be careful when naming boolean fields with "is" prefixes when combining Lombok and Jackson, as it alters the JSON property name.

---

## 8. HikariCP Thread Starvation or Clock Leap Detected

- **Problem:** The `user-service` and other microservices periodically logged `WARN: HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=15m30s103ms)`.
- **Root Cause:** In local development on a laptop, when the laptop lid is closed (or the machine sleeps), the OS suspends all Java processes in RAM. HikariCP's housekeeper thread expects to run exactly every 30 seconds to clean up idle database connections. When the laptop wakes up, the thread resumes, checks the system clock, sees a massive unexpected time jump (e.g., 15 minutes disappeared), and logs a warning assuming the server was totally starved of CPU time or manually tampered with.
- **Impact:** None. It is a completely harmless false alarm in a local laptop environment.
- **Fix:** No fix required. Normal behavior for suspended processes.
- **Key Learning:** Enterprise server software assumes it runs on an always-awake infrastructure. It interprets routine local sleep events as critical performance or scheduling failures.
