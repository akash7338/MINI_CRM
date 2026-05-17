# Telephony Service

## 1. One-Line Purpose

Integrates with Twilio to handle real voice calls — accepts inbound webhook callbacks, generates WebRTC tokens for agents, bridges callers to agents via TwiML, and synchronizes Twilio call status with the internal call lifecycle.

---

## 2. When This Service Comes Into Picture

1. **Twilio inbound webhook** — A real phone call hits the Twilio number, Twilio POSTs to `/twilio/inbound`
2. **Agent needs a WebRTC token** — Browser calls `GET /twilio/token?agentId=AG_001` to enable voice in the browser
3. **Call needs to be bridged** — Twilio redirects to `/twilio/bridge` to connect the caller to the assigned agent
4. **Twilio status callback** — Twilio POSTs to `/twilio/status` when the call starts (`in-progress`) or ends (`completed`)
5. **Routing completed** — Consumes `routing-events` from Kafka to learn which agent was assigned to a Twilio call

---

## 3. Responsibilities

1. **Inbound Call Handling** — Receives Twilio webhook, creates an internal call in call-service via REST, saves a `TelephonyCallSession` to map Twilio's `CallSid` ↔ internal `callId`
2. **TwiML Generation** — Returns XML responses that tell Twilio what to do (play a message, wait, bridge to agent)
3. **Agent Token Generation** — Creates Twilio Access Tokens with VoiceGrant for WebRTC audio in the browser, with a 5-second in-memory cache
4. **Call Bridging** — When an agent is assigned, returns TwiML with `<Dial><Client>AG_001</Client></Dial>` to connect the caller to the agent's browser
5. **Wait Polling Loop** — If no agent is assigned yet, returns TwiML with a message + redirect that creates a polling loop
6. **Status Callback Processing** — Maps Twilio status changes to internal call lifecycle transitions (in-progress → startCall, completed → completeCall)
7. **Assignment Tracking** — Consumes routing events to store the assigned agent ID in the telephony session

---

## 4. APIs Exposed

| Endpoint | Method | Called By | Purpose |
|---|---|---|---|
| `POST /api/v1/telephony/twilio/inbound` | POST | Twilio webhook | Handle new inbound call. Returns TwiML: "Please wait" + redirect to /bridge |
| `GET /api/v1/telephony/twilio/bridge` | GET | Twilio redirect | If agent assigned: return `<Dial><Client>` TwiML. If not: return "still in queue" + redirect back |
| `GET /api/v1/telephony/twilio/token` | GET | Agent's browser | Generate Twilio Access Token with VoiceGrant for WebRTC |
| `POST /api/v1/telephony/twilio/status` | POST | Twilio webhook | Handle call status changes (ringing, in-progress, completed) |
| `POST /api/v1/telephony/twilio/wait` | POST | Twilio redirect | Hold music / "still in queue" message with polling redirect |

### All Twilio endpoints are open (no JWT required)
The API Gateway's `OPEN_ENDPOINTS` list includes `/api/v1/telephony/twilio/` because Twilio webhooks don't carry JWTs.

---

## 5. Kafka Usage

### Consumes ← `routing-events`
- **Consumer:** `RoutingEventConsumer` (group: `telephony-service-group`)
- **Handler:** `TelephonyService.handleAssignment(RoutingEvent event)`
- **Only reacts to:** `ASSIGNED` status (guards with `if (!"ASSIGNED".equals(event.getStatus())) return;`)
- **Logic:**
  - `repository.findByInternalCallId(event.getCallId())` — looks up session by internal call ID
  - `if (session.getAssignedAgentId() == null)` — idempotency guard, only updates once
  - `session.setAssignedAgentId(event.getAgentId())` → `repository.save(session)`
  - This is what the `/bridge` endpoint checks on each poll to decide whether to connect the caller
- **If session not found:** throws `RuntimeException("Telephony session not found")` — triggers Kafka retry

### Produces → `telephony-events`
- **Producer:** `TelephonyService.handleStatusCallback()` — inline `kafkaTemplate.send()`
- **Topic:** `"telephony-events"`, partition key = `session.getTenantId()`
- **Payload:** `TelephonyEvent { eventType="TELEPHONY_STATUS_UPDATE", callSid, callStatus, internalCallId, from, to, tenantId, timestamp }`
- **When:** After every Twilio status callback (`ringing`, `in-progress`, `completed`)
- **Note:** This topic currently has **no consumers** — published for future audit/analytics use. The comment in source says: *"In a full production app, we would use a Transactional Outbox here"*

---

## 6. Redis Usage

**None.** The telephony service uses PostgreSQL for session storage and **two in-memory `ConcurrentHashMap` instances** for token caching:
```java
Map<String, Map<String, String>> tokenCache   // agentId → { token, identity }
Map<String, Long>               tokenExpiry   // agentId → expiryEpochMs (now + 5000)
```
This avoids hitting the Twilio API on every browser page load or SockJS poll.

---

## 7. PostgreSQL Usage

### Database: `minigenesys_telephony`

### Table: `telephony_call_sessions`
| Column | Type | Description |
|---|---|---|
| `id` | UUID (PK, auto-generated) | Session record ID |
| `tenant_id` | VARCHAR | Multi-tenant scope |
| `twilio_call_sid` | VARCHAR (unique) | Twilio's external call identifier |
| `internal_call_id` | VARCHAR | Maps to the call-service's `calls.id` |
| `assigned_agent_id` | VARCHAR (nullable) | Set when routing-event ASSIGNED arrives |
| `from_number` | VARCHAR | Caller's phone number |
| `to_number` | VARCHAR | Twilio number that was called |
| `status` | VARCHAR | Twilio call status (ringing, in-progress, completed) |
| `created_at` | TIMESTAMP | Auto-set |
| `updated_at` | TIMESTAMP | Auto-set |

### Key Queries
- `findByTwilioCallSid(callSid)` — Idempotency check on inbound + bridge lookup
- `findByInternalCallId(callId)` — Assignment update from routing events

---

## 8. Important State Changes

### Twilio ↔ Internal Call ID Mapping
```
Twilio Call SID: CA1234567890abcdef...
        ↕ (mapped via telephony_call_sessions table)
Internal Call ID: 70ab5ca5-5823-42b0-9c1c-9bb4eedd6cdb
```

### Call Flow Through Telephony Service
```
1. Inbound webhook (Twilio → POST /twilio/inbound?CallSid=CA...&From=+1...&To=+1...)
   TelephonyController.handleInbound()
   → telephonyService.handleInboundCall(callSid, from, to, tenantId)
       → repository.findByTwilioCallSid(callSid)  [idempotency check]
           if exists → return existing.internalCallId (Twilio retry safe)
       → callServiceClient.createInternalCall(tenantId, from)  [REST to call-service]
           → returns internalCallId (UUID)
       → saveNewSession(callSid, internalCallId, from, to, tenantId)
           → repository.save(TelephonyCallSession { status="ringing" })  [PG INSERT]
   → controller returns TwiML directly:
       <?xml?>
       <Response>
         <Say>Please wait while we connect you to an agent.</Say>
         <Pause length="3"/>
         <Redirect method="GET">/api/v1/telephony/twilio/bridge?callSid={callSid}</Redirect>
       </Response>

2. /bridge polled by Twilio (GET /twilio/bridge?callSid=CA...)
   TelephonyController.bridge()
   → telephonyService.getBridgeTwiml(callSid)
       → repository.findByTwilioCallSid(callSid)
       → if session.assignedAgentId != null:
           return <Dial answerOnBridge="true"><Client>AG_001</Client></Dial>
       → else (still waiting):
           return <Say>Your call is still in queue</Say>
                  <Pause length="3"/>
                  <Redirect>/bridge?callSid={callSid}</Redirect>   ← creates polling loop

3. Routing assigns agent (Kafka: routing-events ASSIGNED)
   TelephonyService.handleAssignment()
   → session.setAssignedAgentId(agentId)  [PG UPDATE]
   Next /bridge poll → finds assignedAgentId → returns <Dial><Client> TwiML

4. Status callback: in-progress (Twilio → POST /twilio/status?CallStatus=in-progress)
   TelephonyController.handleStatus()
   → telephonyService.handleStatusCallback(callSid, "in-progress", from, to)
       → session.setStatus("in-progress"), repository.save()  [PG UPDATE]
       → callServiceClient.startCall(tenantId, internalCallId)
           → REST POST /api/v1/calls/{callId}/start → call-service: status = IN_PROGRESS
       → kafkaTemplate.send("telephony-events", tenantId, TelephonyEvent)

5. Status callback: completed (Twilio → POST /twilio/status?CallStatus=completed)
   TelephonyController.handleStatus()
   → telephonyService.handleStatusCallback(callSid, "completed", from, to)
       → session.setStatus("completed"), repository.save()  [PG UPDATE]
       → callServiceClient.completeCall(tenantId, internalCallId)
           → REST POST /api/v1/calls/{callId}/complete
           → call-service publishes CALL_COMPLETED to call-lifecycle-events
           → agent-state-service releases agent → AVAILABLE
       → kafkaTemplate.send("telephony-events", tenantId, TelephonyEvent)
```

**Important implementation detail:** `handleStatusCallback()` has a commented-out block in the source that shows the original intent was to handle `canceled`, `no-answer`, and `failed` statuses the same as `completed`. Currently only `completed` triggers `completeCall()`. This is a known simplification.

---

## 9. Interaction With Other Services

| Direction | Service | How | Why |
|---|---|---|---|
| **Calls →** | Call Service | REST via `CallServiceClient` | `createInternalCall()`, `startCall()`, `completeCall()` |
| **Consumes ←** | Routing Service | Kafka `routing-events` | Learn which agent was assigned to bridge the Twilio call |
| **Called by ←** | Twilio Cloud | HTTP webhooks | Inbound calls, status callbacks |
| **Called by ←** | Agent's browser | REST `GET /twilio/token` | Get WebRTC access token |

### Token Generation Detail
```java
AccessToken token = new AccessToken.Builder(accountSid, apiKeySid, apiKeySecret)
    .identity(agentId)        // "AG_001"
    .grant(voiceGrant)        // VoiceGrant with incomingAllow=true
    .build();
```
The `identity` field is the agent ID. When Twilio sees `<Client>AG_001</Client>` in the TwiML, it routes the audio to the browser that registered with identity `AG_001`.

---

## 10. Edge Cases / Failure Scenarios

| Scenario | What Happens |
|---|---|
| **Duplicate inbound webhook (Twilio retries)** | `findByTwilioCallSid()` returns existing session → returns the existing `internalCallId` instead of creating a duplicate call |
| **Agent not assigned yet when /bridge is called** | Returns TwiML with "Your call is still in queue" + 3-second pause + redirect back to /bridge, creating a polling loop |
| **Call service is down during inbound** | `createInternalCall()` throws exception → Twilio gets a 500 → Twilio will retry the webhook |
| **Status callback arrives before routing is complete** | `handleStatusCallback` finds the session, updates status, but `startCall()` may fail with 409 (call not in ROUTED status yet). Error is caught and logged. |
| **Token generation under load** | Uses a 5-second in-memory cache (`ConcurrentHashMap`) to prevent generating a new Twilio token on every browser poll |
| **Assignment already set (duplicate routing event)** | `handleAssignment()` checks `if (session.getAssignedAgentId() == null)` before updating — idempotent |

---

## 11. Interview Explanation

> "The telephony-service is the bridge between the Twilio Voice platform and our internal call system. When a real phone call comes in, Twilio POSTs to our webhook with the call SID and caller info. The service creates an internal call record via REST call to the call-service, saves a mapping between Twilio's Call SID and our internal call ID in PostgreSQL, and returns TwiML XML that tells Twilio to play 'Please wait' and redirect to a bridge endpoint. That bridge endpoint polls — if no agent has been assigned yet, it returns a 'still in queue' message with a 3-second loop. Once the routing-service assigns an agent (consumed via Kafka), it stores the agent ID in the session. On the next bridge poll, it returns `<Dial answerOnBridge='true'><Client>AG_001</Client></Dial>` which tells Twilio to connect the caller's audio to that agent's WebRTC browser session. The agent's browser gets its WebRTC capability by requesting a Twilio Access Token with a VoiceGrant from our token endpoint."

---

## 12. Annotated Flow Traces (Exact Methods)

### Token Generation
```
Browser: GET /api/v1/telephony/twilio/token?agentId=AG_001
→ TelephonyController.getToken("AG_001")
→ TelephonyService.generateToken("AG_001")
    → tokenCache.containsKey("AG_001") && now < tokenExpiry.get("AG_001")?
        YES → return cached { token, identity }  (5-second cache)
        NO  → build token:
            VoiceGrant grant = new VoiceGrant()
            grant.setIncomingAllow(true)  ← browser can receive calls
            grant.setOutgoingApplicationSid(twimlAppSid)  ← can make calls
            AccessToken token = new AccessToken.Builder(accountSid, apiKeySid, apiKeySecret)
                .identity("AG_001")   ← this is what <Client>AG_001</Client> targets
                .grant(grant)
                .build()
            tokenCache.put("AG_001", { token: jwt, identity: "AG_001" })
            tokenExpiry.put("AG_001", now + 5000)
    → returns { "token": "eyJ...", "identity": "AG_001" }
```

### Kafka Consumer: routing-events ASSIGNED
```
Kafka: routing-events { status="ASSIGNED", callId="abc", agentId="AG_001", tenantId="tenant1" }
→ RoutingEventConsumer.handleRoutingEvent(String message)
→ TelephonyService.handleAssignment(RoutingEvent event)
    → if event.status != "ASSIGNED" → return
    → repository.findByInternalCallId("abc")
        if not found → throw RuntimeException (Kafka will retry)
    → if session.assignedAgentId == null:  [idempotency guard]
        session.setAssignedAgentId("AG_001")
        repository.save(session)  [PG UPDATE: assigned_agent_id = AG_001]
    Now next /bridge poll will return <Dial><Client>AG_001</Client></Dial>
```

