# Call Service

## 1. One-Line Purpose

Manages the entire call lifecycle from creation to completion — persists call records, publishes events to trigger routing, handles agent disconnect recovery by requeuing orphaned calls, and processes routing results.

---

## 2. When This Service Comes Into Picture

1. **Call created** — Agent clicks "Simulate Call" or telephony-service creates a call via REST
2. **Routing completed** — Consumes `routing-events` to update call status (QUEUED → ROUTED or QUEUED → FAILED)
3. **Call started** — Agent clicks "Start Call" (ROUTED → IN_PROGRESS)
4. **Call completed** — Agent clicks "Complete Call" (IN_PROGRESS → COMPLETED), publishes lifecycle event
5. **Agent disconnects** — Consumes `agent-events` for AGENT_DISCONNECTED, finds all active calls for that agent, requeues them

---

## 3. Responsibilities

1. **Call Creation** — Accepts new call requests, persists them in PostgreSQL, publishes to `call-events` Kafka topic
2. **Call State Transitions** — Enforces strict status progression: QUEUED → ROUTED → IN_PROGRESS → COMPLETED
3. **Routing Result Processing** — Consumes `routing-events` to update call status and assigned agent
4. **Agent Disconnect Recovery** — When an agent disconnects, finds their active calls and requeues them for re-routing
5. **Call Completion Events** — Publishes `CALL_COMPLETED` to `call-lifecycle-events` topic (triggers agent release)
6. **Tenant Isolation** — All queries scoped by `tenantId`, enforces access control

---

## 4. APIs Exposed

| Endpoint | Method | Purpose |
|---|---|---|
| `POST /api/v1/calls` | POST | Create a new call. Body: `{callerId, requiredSkills, priority}` |
| `GET /api/v1/calls/{callId}` | GET | **State Restoration:** Fetched by frontend during browser refresh if agent is currently assigned to a call, getting true PG status instead of guessing |
| `POST /api/v1/calls/{callId}/start` | POST | Transition ROUTED → IN_PROGRESS |
| `POST /api/v1/calls/{callId}/complete` | POST | Transition IN_PROGRESS → COMPLETED, publishes `CALL_COMPLETED` lifecycle event |
| `POST /api/v1/calls/{callId}/reject` | POST | Transition ROUTED → QUEUED. Requeues call (`newCall: false`) AND publishes `CALL_COMPLETED` to free the rejecting agent |

### Validation Rules
- `startCall()` — Call must be in `ROUTED` status, otherwise `409 CONFLICT`
- `completeCall()` — Call must be in `IN_PROGRESS` status, otherwise `409 CONFLICT`
- `rejectCall()` — Call must be in `ROUTED` status, otherwise `409 CONFLICT`
- `getCall()` — `tenantId` must match, otherwise `403 FORBIDDEN`

---

## 5. Kafka Usage

### Produces → `call-events`
| When | Payload |
|---|---|
| New call created | `{callId, tenantId, requiredSkills, priority, isNew: true}` |
| Call requeued after agent disconnect | `{callId, tenantId, requiredSkills, priority, isNew: false}` |

The `isNew: false` flag is critical — it tells the analytics-service not to double-count this call.

### Produces → `call-lifecycle-events`
| When | Payload |
|---|---|
| Call completed | `{eventType: "CALL_COMPLETED", callId, tenantId, agentId}` |

This event triggers the agent-state-service to release the agent back to AVAILABLE.

### Consumes ← `routing-events`
- **Consumer:** `RoutingEventConsumer` (group: `call-service-group`)
- **Handler:** `CallService.handleRoutingEvent(RoutingEvent event)`
- **Logic (from source):**
  - `"ASSIGNED"` → `call.setStatus(ROUTED)`, `call.setAssignedAgentId(event.agentId)`, save
  - `"NO_AGENT"` → `call.setStatus(QUEUED)`, `call.setRoutingFailureReason(msg)`, save
  - `"ERROR"` or `"failed"` → `call.setStatus(FAILED)`, save
  - `"ABANDONED"` → `call.setStatus(ABANDONED)`, save. **Also:** if `call.assignedAgentId != null`, publishes a `CALL_COMPLETED` lifecycle event to free the agent (even on abandonment)

### Consumes ← `agent-events`
- **Consumer:** `AgentEventConsumer` (group: `call-service-agent-recovery-group`)
- **Handler:** `CallService.handleAgentDisconnect()`
- **Only reacts to:** `AGENT_DISCONNECTED` events
- **Logic:** Finds all calls with `assignedAgentId = disconnectedAgent` AND `status IN (ROUTED, IN_PROGRESS)`, sets them to QUEUED, clears the agent, and republishes to `call-events`

---

## 6. Redis Usage

**None.** The call-service is purely PostgreSQL-based. All call state is stored in the `calls` table. This is intentional — call records need strong consistency and durability, not sub-millisecond access.

---

## 7. PostgreSQL Usage

### Database: `minigenesys_calls`

### Table: `calls`
| Column | Type | Description |
|---|---|---|
| `id` | UUID (PK, auto-generated) | Unique call identifier |
| `tenant_id` | VARCHAR | Multi-tenant scope |
| `status` | ENUM (CREATED, QUEUED, ROUTED, IN_PROGRESS, COMPLETED, FAILED) | Current call state |
| `priority` | INTEGER | Routing priority (higher = more urgent) |
| `assigned_agent_id` | VARCHAR (nullable) | The agent currently handling this call |
| `routing_failure_reason` | VARCHAR (nullable) | Why routing failed (e.g., "No available agent") |
| `created_at` | TIMESTAMP | Auto-set |
| `updated_at` | TIMESTAMP | Auto-updated |

### Table: `call_skills` (ElementCollection)
| Column | Type | Description |
|---|---|---|
| `call_id` | UUID (FK) | Links to calls.id |
| `skill` | VARCHAR | Required skill (e.g., "sales") |

### Key Queries
- `findById(callId)` — Get call by ID
- `findByAssignedAgentIdAndStatusIn(agentId, statuses)` — Find active calls for a disconnecting agent

---

## 8. Important State Changes

### Call Status State Machine
```
  [QUEUED] ──── ASSIGNED ────► [ROUTED] ──── startCall() ────► [IN_PROGRESS] ──── completeCall() ────► [COMPLETED]
     ▲    │                    │
     │    NO_AGENT            reject()
     │    (stays QUEUED)       │
     │                         ▼
     │                    REQUEUE (isNew=false)
     │                    → re-enters routing
     │
     └── agent disconnect ──── requeue ──── republish to call-events

  [FAILED] ◄── ERROR routing
  [ABANDONED] ◄── max retries exceeded (10 retries, ~114s)
```

### Status Descriptions
| Status | Meaning |
|---|---|
| `QUEUED` | Waiting for an available agent |
| `ROUTED` | Agent assigned, call ringing on agent's browser |
| `IN_PROGRESS` | Agent has accepted and is actively on the call |
| `COMPLETED` | Call finished normally |
| `FAILED` | Routing error or system failure |

---

## 9. Interaction With Other Services

| Direction | Service | How | Why |
|---|---|---|---|
| **Called by ←** | API Gateway | HTTP proxy | External call creation, start, complete |
| **Called by ←** | Telephony Service | REST `POST /api/v1/calls`, `POST /{id}/start`, `POST /{id}/complete` | Twilio webhooks create/start/complete real calls |
| **Consumes ←** | Routing Service | Kafka `routing-events` | Updates call status based on routing decisions |
| **Consumes ←** | Agent State Service | Kafka `agent-events` | Detects agent disconnects to trigger call recovery |
| **Produces →** | Kafka `call-events` | New/requeued calls | Consumed by routing-service to trigger agent matching |
| **Produces →** | Kafka `call-lifecycle-events` | Call completed | Consumed by agent-state-service to release agent |

---

## 10. Edge Cases / Failure Scenarios

| Scenario | What Happens |
|---|---|
| **Agent disconnects with active call** | `handleAgentDisconnect()` finds the call, sets status back to QUEUED, clears assignedAgentId, republishes to Kafka. Routing-service picks it up and tries to find a new agent |
| **Agent disconnects with no active calls** | `findByAssignedAgentIdAndStatusIn()` returns empty list, no action taken |
| **Start call on a QUEUED call** | `409 CONFLICT: Call must be in ROUTED status to start` |
| **Complete call on a ROUTED call** | `409 CONFLICT: Call must be in IN_PROGRESS status to complete` |
| **Same call requeued multiple times** | Each requeue publishes `isNew: false` to prevent double-counting in analytics. The routing-service's idempotency cache may need to be cleared (now handled by our fix) |
| **Call completed after agent already disconnected** | If the call was already requeued and assigned to a new agent, the completion event from the old session is harmless — the call has a new `assignedAgentId` |
| **Tenant mismatch in routing event** | `handleRoutingEvent()` checks `event.getTenantId()` against `call.getTenantId()` and logs an error if they differ |

---

## 11. Interview Explanation

> "The call-service owns the call lifecycle. When a call is created — either simulated from the dashboard or from a real Twilio inbound — it's persisted in PostgreSQL with status QUEUED and an event is published to Kafka. The routing-service picks it up, finds an agent, and publishes an ASSIGNED event back. The call-service consumes that event and updates the call to ROUTED. The agent then starts and completes the call via REST endpoints, with strict state validation at each step. The most interesting part is the agent disconnect recovery — the service listens for AGENT_DISCONNECTED events from Kafka, finds all active calls for that agent, resets them to QUEUED, and republishes them to the call-events topic with a `isNew: false` flag so they get re-routed to a different agent without being double-counted in analytics."

---

## 12. Annotated Flow Traces (Exact Methods)

### Flow 1: Call Created
```
Browser: POST /api/v1/calls  { callerId, requiredSkills: ["sales"], priority: 2 }
→ CallController.createCall(@RequestHeader X-Tenant-Id, @RequestBody)
→ CallService.createCall(tenantId, request)
    → priority = request.getPriority() ?? 1
    → Call.builder().status(QUEUED)....build()
    → callRepository.save(call)  [PG INSERT: calls table]
    → CallEvent.builder().callId(call.id).tenantId(...).requiredSkills(...).priority(...)build()
    → callEventProducer.publishCallEvent(event)
        → kafkaTemplate.send("call-events", tenantId, message)
          → KafkaMessaging.consumeCallEvent() (routing-service)  ← NEXT
    → returns CallResponse { id, status=QUEUED, ... }
```

### Flow 2: Routing Result Arrives
**Consumer:** `RoutingEventConsumer` (group: `call-service-group`)
```
Kafka: routing-events message
→ CallService.handleRoutingEvent(RoutingEvent event)
    → callRepository.findById(event.callId)  [PG SELECT]
    → tenant safety check: event.tenantId == call.tenantId
    → switch(event.status):
        "ASSIGNED":
          call.setStatus(ROUTED)
          call.setAssignedAgentId(event.agentId)
          call.setRoutingFailureReason(null)
        "NO_AGENT":
          call.setStatus(QUEUED)  [stays in queue]
          call.setRoutingFailureReason(event.message)
        "ERROR" / "failed":
          call.setStatus(FAILED)
        "ABANDONED":
          call.setStatus(ABANDONED)
          if call.assignedAgentId != null:
            callEventProducer.publishLifecycleEvent(CALL_COMPLETED)
              ← forces agent back to AVAILABLE even on abandoned calls
    → callRepository.save(call)  [PG UPDATE]
```

### Flow 3: Agent Rejects Call
```
Browser: POST /api/v1/calls/{callId}/reject
→ CallController.rejectCall()
→ CallService.rejectCall(callId, tenantId)
    → callRepository.findById(callId)
    → if call.status != ROUTED → throw 409 CONFLICT
    → rejectedAgentId = call.getAssignedAgentId()
    → call.setStatus(QUEUED)
    → call.setAssignedAgentId(null)
    → callRepository.save(call)  [PG UPDATE: status=QUEUED, assigned_agent_id=null]
    → callEventProducer.publishCallEvent(
          CallEvent { callId, tenantId, requiredSkills, priority, newCall=false })
      ← "newCall=false" prevents double-counting in analytics
      → Kafka: call-events → routing-service tries again
    → if rejectedAgentId != null:
        callEventProducer.publishLifecycleEvent(
            CallLifecycleEvent { eventType="CALL_COMPLETED", callId, tenantId, agentId=rejectedAgentId })
        → Kafka: call-lifecycle-events
          → CallLifecycleConsumer (agent-state-service):
              AgentStateService.handleCallCompletion() → agent → AVAILABLE
```

### Flow 4: Agent Disconnect Recovery
**Consumer:** `AgentEventConsumer` (group: `call-service-agent-recovery-group`)
```
Kafka: agent-events { eventType="AGENT_DISCONNECTED", agentId, tenantId }
→ CallService.handleAgentDisconnect(AgentEvent event)
    → if event.eventType != "AGENT_DISCONNECTED" → return
    → callRepository.findByAssignedAgentIdAndStatusIn(agentId, [ROUTED, IN_PROGRESS])
       [PG: SELECT * FROM calls WHERE assigned_agent_id=? AND status IN ('ROUTED','IN_PROGRESS')]
    → if empty → log, return
    → for each active call:
        if call.status == QUEUED: skip (idempotency — already requeued)
        call.setStatus(QUEUED)
        call.setAssignedAgentId(null)
        callRepository.save(call)  [PG UPDATE]
        callEventProducer.publishCallEvent(CallEvent { ..., newCall=false })
          → Kafka: call-events → routing-service picks up orphaned call
```

