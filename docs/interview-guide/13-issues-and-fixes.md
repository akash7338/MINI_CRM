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

---

## 9. "Call in Queue" (Agent State Sync Failure After Twilio Disconnect)

- **Problem:** When an agent picks up a live Twilio call and then disconnects via the softphone, consecutive inbound calls fail to connect and go straight to "Call in Queue", eventually abandoning.
- **Root Cause:** The Angular frontend did not correctly notify the backend (`call-service`) that the call had ended. Although we previously added `apiService.updateCallStatus(callId, 'COMPLETED')` on hangup, it was failing silently with a **400 Bad Request**. The `CallController` in the backend explicitly requires the `X-Tenant-Id` header (`@RequestHeader(required = true)`), but the `updateCallStatus` method in `api.service.ts` was not appending this header. Because the API request failed, `call-service` never transitioned the call to `COMPLETED`, the `CALL_COMPLETED` lifecycle event was never published, and the `agent-state-service` never reset the agent from `BUSY` back to `AVAILABLE`.
- **Impact:** Agents remain permanently locked in a `BUSY` state after their first call, breaking the routing engine and causing 100% of subsequent calls to be queued.
- **Fix:** 
  1. Updated `telephony.service.ts` and `telephony-overlay.component.ts` on the frontend to explicitly call `ApiService.updateCallStatus()`. 
  2. Fixed `api.service.ts` to append `options.headers.set('X-Tenant-Id', this.tenantId)` to the `updateCallStatus` POST request, allowing the API call to succeed.
- **Key Learning:** When adding new API calls to frontend services, always verify the exact header requirements (`required = true`) of the backend controller. A silent HTTP 400 error in a state-critical API can permanently break the distributed state machine.

---

## 10. Phantom Disconnects / Active Call Requeued on Browser Refresh

- **Problem:** An agent was actively engaged in a call. Without warning, the UI appeared to briefly flicker/reload. A few seconds later, the call was abruptly stripped from them, sent back to the queue, and eventually abandoned, despite the agent never explicitly ending the call.
- **Root Cause:** When running locally, saving a file in the code editor triggers an **Angular Live Reload (HMR)**, which is identical to a hard browser refresh. When the app reloaded, `SessionStateService` successfully rehydrated the agent's state from the backend (knowing they were `On Call`). However, the `startHeartbeat()` method was omitted during this rehydration flow. The Angular app stopped sending its 15-second heartbeats. Exactly 30 seconds later, the backend `agent-state-service` assumed the agent had crashed, marking them `OFFLINE`. This triggered an agent recovery event in `call-service`, which stripped the active call and requeued it to protect the customer. When the next Live Reload hit, the agent state loaded as `OFFLINE`, and the call disappeared from the UI entirely.
- **Impact:** Any page refresh (or live reload) during an active call guaranteed the call would be dropped and requeued 30 seconds later.
- **Fix:** Updated `SessionStateService.loadInitialState()` to explicitly call `this.startHeartbeat()` if the rehydrated UI status is not `Offline`. 
- **Key Learning:** State rehydration is a two-part process. Restoring the visual UI state is not enough; you must also explicitly restore any background tasks, loops, or heartbeats that maintain the infrastructure contract for that state.

---

## 11. Agent Locked in BUSY Status if Caller Hangs Up While Ringing

- **Problem:** An inbound call rings on the agent's screen. Before the agent can click "Accept", the caller hangs up. The ringing stops and the popup disappears, but the agent's status remains locked in `BUSY` on the backend, preventing them from receiving any future calls.
- **Root Cause:** The `telephony.service.ts` successfully listened to the Twilio `call.on('cancel')` event (triggered when the caller hangs up early) and cleared the UI popup. However, it failed to notify the `call-service` backend that the call had been abandoned/completed. Since the backend had already `ASSIGNED` the call to the agent and marked them `BUSY`, it waited indefinitely for the call to finish.
- **Impact:** Agents become permanently locked if a caller hangs up before they answer.
- **Fix:** Updated the `call.on('cancel')` listener in `telephony.service.ts` to explicitly call `apiService.updateCallStatus(callId, 'COMPLETED')`. This ensures the backend clears the abandoned call and transitions the agent back to `AVAILABLE`.
- **Key Learning:** Edge cases in the state machine (like abandoning a call before it connects) require the same explicit backend synchronization as standard happy-path flows.

---

## 12. Routing Service Crash on Requeue and Permanent Agent Lock on Abandonment

- **Problem:** When an active call was requeued (e.g., due to an agent going offline), the `routing-service` violently crashed with a `duplicate key value violates unique constraint` Postgres error. It then threw the call into a retry loop, crashing 10 times in a row. Finally, it abandoned the call, but left the original agent permanently locked in `BUSY`.
- **Root Cause:** 
  1. **Postgres Crash:** The `assignments` table in `routing-service` had a `unique = true` constraint on `call_id`. When the `routing-service` picked up the requeued call and tried to assign it, it blindly attempted to insert a *new* row into the table with the same `call_id`. This violated the Postgres constraint, crashing the assignment logic and forcing the call into the error queue.
  2. **Agent Lock:** After failing 10 times, the `RetryProcessor` published an `ABANDONED` event. However, the `CallService` (which listens to routing events) didn't execute any agent cleanup logic for `ABANDONED` calls. It just left the call status as `ABANDONED` and never published a `CALL_COMPLETED` event, leaving the original assigned agent stuck as `BUSY` forever.
- **Impact:** Requeued calls never reached a new agent, generated massive database error spam, and permanently corrupted the original agent's state, preventing them from receiving future calls.
- **Fix:** 
  1. Updated `RoutingEngine.java` to use an "Upsert" pattern: `assignmentRepository.findByCallId(callId).orElseGet(...)`. Now, when a call is reassigned, it smoothly updates the existing database record.
  2. Updated `CallService.java` to explicitly handle the `ABANDONED` routing status. If a call is abandoned and has an assigned agent, it now publishes a `CALL_COMPLETED` event to force the `agent-state-service` to free the stuck agent.
- **Key Learning:** In distributed state machines, database constraints (like `UNIQUE`) can completely break recovery/requeue flows. Additionally, every terminal state in a workflow (including failure states like `ABANDONED`) must have explicitly mapped cleanup logic to prevent resource leaks (stuck agents).

---

## 13. UI State Desynchronization and Event Loss (Angular Zone & Subscription Race)

- **Problem:** When an inbound call was routed and assigned, the agent could hear the phone ring (via Twilio), but the dashboard UI failed to show the "Call Panel" and the agent status remained "Ready" instead of "On Call". Sometimes, details would mysteriously appear several minutes later, often after a recovery event.
- **Root Cause:**
    1. **Angular Zone Issue:** WebSocket events (STOMP) and Telephony events (Twilio SDK) were firing from background threads outside of Angular's `NgZone`. Because of this, even when the frontend received the data, Angular's change detection wasn't triggered, and the UI didn't update to reflect the new state until a manual interaction (like a mouse click) forced a refresh.
    2. **Subscription Race Condition:** The `WebsocketService` initialized at app boot. If it connected before the user logged in, it didn't have the `tenantId` yet and thus never subscribed to the relevant event topic. Even after login, it remained connected but "deaf" to events because the subscription logic only fired once on the initial connection.
    3. **STOMP Connection Race (White Screen Crash):** After implementing the dynamic subscription fix, a new race condition emerged. `SessionStateService` tried to subscribe to tenant events immediately upon initialization. However, because the STOMP connection is asynchronous, calling `stompClient.subscribe()` before the connection was fully established resulted in a `TypeError: There is no underlying STOMP connection`. This crash occurred during the Angular bootstrap phase, resulting in a permanent white screen.
- **Impact:** Agents were left "flying blind"—they could answer calls but had no UI control or visibility into call details. The system state appeared "laggy" or inconsistent.
- **Fix:**
    1. **NgZone Wrapping:** Updated `WebsocketService` and `TelephonyService` to wrap all event emissions and state updates in `this.zone.run(() => { ... })`. This forces Angular to refresh the UI the moment a message arrives.
    2. **Dynamic Subscription:** Added a `subscribeToTenantEvents()` method to `WebsocketService` that is explicitly called during `SessionStateService` initialization (after login/rehydration). This ensures the correct tenant topic is always subscribed to, even if the connection was established prior to login.
    3. **STOMP State Gating:** Updated `WebsocketService.subscribeToTenantEvents()` to gate the subscription call with a `stompClient.connected` check. If the connection is not yet ready, the method exits gracefully. To ensure the subscription eventually happens, a call to `subscribeToTenantEvents()` was added to the WebSocket `onConnect` callback, allowing the system to "self-heal" and subscribe as soon as the line is open.
- **Key Learning:** In real-time Angular applications, background SDKs (WebSockets, Twilio, etc.) must be bridged back into the Angular Zone. Additionally, state-dependent subscriptions (like per-tenant event topics) must be re-evaluated whenever the session state changes, not just on initial connection.
