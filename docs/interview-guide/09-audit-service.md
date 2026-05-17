# Audit Service

## 1. One-Line Purpose

An immutable event recorder — consumes every Kafka event across all 5 topics and persists each one as a structured, queryable audit trail in PostgreSQL.

---

## 2. When This Service Comes Into Picture

The audit service is always listening, always recording. Every single event that flows through the system ends up here:

- Agent logs in → `agent-events` → audit record
- Call created → `call-events` → audit record
- Call assigned → `routing-events` → audit record
- Call completed → `call-lifecycle-events` → audit record
- User logs in → `user-events` → audit record

It is the **forensic log** of the entire platform.

---

## 3. Responsibilities

1. **Universal Event Consumption** — Listens to all 5 Kafka topics in the system
2. **Structured Persistence** — Parses JSON payloads, extracts metadata (tenantId, entityType, entityId, eventType, sourceService), and saves to PostgreSQL
3. **Source Service Attribution** — Maps each topic to its originating service for traceability
4. **Query API** — Exposes a filterable GET endpoint for searching audit records by tenant, entity, and event type
5. **Immutable Storage** — Records are only ever INSERTed, never UPDATEd or DELETEd

---

## 4. APIs Exposed

| Endpoint | Method | Purpose |
|---|---|---|
| `GET /api/v1/audit/events` | GET | Query audit events with filters |

### Query Parameters
| Parameter | Required | Description |
|---|---|---|
| `tenantId` | No | Filter by tenant |
| `entityType` | No | Filter by entity type (CALL, AGENT, USER) |
| `entityId` | No | Filter by specific entity ID |
| `eventType` | No | Filter by event type (AGENT_AVAILABLE, CALL_COMPLETED, etc.) |
| `limit` | No (default: 100) | Max number of results |

### Example Query
```
GET /api/v1/audit/events?tenantId=tenant1&entityType=AGENT&entityId=AG_001&limit=50
```

Returns the last 50 audit events related to agent AG_001.

---

## 5. Kafka Usage

### Consumes ← 5 Topics (single method, `@Header` injection)

**Consumer class:** `KafkaAuditConsumer.consume(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic)`
**Group:** `audit-service-group`
**Annotation:** `@Transactional` — every consume() call is a DB transaction, so a failed INSERT rolls back cleanly

| Topic | Source Service | Entity Type Extracted |
|---|---|---|
| `call-events` | call-service | CALL (via `callId` field) |
| `routing-events` | routing-service | CALL (via `callId` field) |
| `agent-events` | agent-state-service | AGENT (via `agentId` field) |
| `call-lifecycle-events` | call-service | CALL (via `callId` field) |
| `user-events` | user-service | USER (via `userId` field) |

**`eventType` extraction (from source):**
```java
String eventType = node != null && node.has("eventType") ? node.get("eventType").asText() : topic;
```
If the event has no `eventType` field, the **topic name itself** is used as the event type. This means call-events without an explicit eventType are recorded as `"call-events"` — not null.

**Entity extraction priority (from source):**
```java
if (node.has("callId"))       → entityType = "CALL",  entityId = callId
else if (node.has("agentId")) → entityType = "AGENT", entityId = agentId
else if (node.has("userId"))  → entityType = "USER",  entityId = userId
// else: both null
```

**Source service mapping (from `getSourceService()`):**
```java
"call-events" / "call-lifecycle-events" → "call-service"
"routing-events"                        → "routing-service"
"agent-events"                          → "agent-state-service"
"user-events"                           → "user-service"
default                                 → "unknown"
```

### Does NOT Produce
The audit service never publishes to any Kafka topic.

---

## 6. Redis Usage

**None.** Pure PostgreSQL.

---

## 7. PostgreSQL Usage

### Database: `minigenesys_audit`

### Table: `audit_events`
| Column | Type | Description |
|---|---|---|
| `id` | UUID (PK, auto-generated) | Unique audit record ID |
| `tenant_id` | VARCHAR (nullable) | Multi-tenant scope |
| `actor_user_id` | VARCHAR (nullable) | User who triggered the event (if available) |
| `actor_role` | VARCHAR (nullable) | Role of the actor (if available) |
| `entity_type` | VARCHAR (nullable) | CALL, AGENT, or USER |
| `entity_id` | VARCHAR (nullable) | The specific entity's ID |
| `event_type` | VARCHAR (not null) | e.g., AGENT_AVAILABLE, CALL_COMPLETED, ASSIGNED |
| `source_service` | VARCHAR (not null) | Which service produced this event |
| `payload_json` | TEXT (not null) | The complete raw JSON of the original Kafka message |
| `created_at` | TIMESTAMP (not null, auto-set) | When the audit record was created |

### Key Design Decisions
- **`payload_json` stores the full raw message** — This means you never lose data, even if the schema of the source event changes. You can always go back and re-parse the raw JSON
- **Immutable records** — `created_at` is set once and never updated. There is no `updated_at` column. Records are append-only
- **Nullable metadata** — If JSON parsing fails (malformed message), the consumer still saves the record with raw payload and `null` metadata fields

---

## 8. Important State Changes

The audit service has no internal state transitions. Every event results in a single `INSERT`:

```
Kafka Message → Parse JSON → Extract metadata → INSERT into audit_events
```

There is no update, no delete, no deduplication.

---

## 9. Interaction With Other Services

| Direction | Service | How | Why |
|---|---|---|---|
| **Consumes ←** | All producing services | Kafka (5 topics) | Records every system event |
| **Called by ←** | API Gateway | HTTP proxy | Supervisors query audit logs |

Like the analytics service, the audit service is **completely isolated** — it only reads from Kafka and serves queries from its own database.

---

## 10. Edge Cases / Failure Scenarios

| Scenario | What Happens |
|---|---|
| **Malformed JSON message** | `objectMapper.readTree()` catches the exception, logs a warning, saves the record with raw payload and null metadata |
| **Duplicate events from Kafka** | Duplicate records will be inserted (no deduplication). Each has a unique UUID, so they won't conflict |
| **Database full / disk space** | Inserts will fail, Kafka consumer will stop progressing. The DLQ will capture failed messages |
| **High event volume** | Each event = one INSERT. For 100K events/sec, this would require significant database capacity. In production, you'd use batch inserts or a time-series database |
| **Missing tenantId in event** | `tenantId` is set to null in the audit record. The record is still saved |

---

## 11. Interview Explanation

> "The audit service is the immutable event log for the entire platform. It consumes from all five Kafka topics — call-events, routing-events, agent-events, call-lifecycle-events, and user-events — and persists every single message as a structured audit record in PostgreSQL. Each record includes metadata I extract from the JSON: tenant ID, entity type (CALL/AGENT/USER), entity ID, event type, and source service, plus the complete raw JSON payload for forensic analysis. Records are append-only — never updated or deleted. The query API supports filtering by tenant, entity, and event type, which is useful for debugging production issues like the ghost call re-assignment bug we investigated, where the audit trail clearly showed the ASSIGNED → DISCONNECTED → ASSIGNED cycle repeating every 10 seconds."

---

## 12. Annotated Flow Traces (Exact Methods)

### `KafkaAuditConsumer.consume()` — full trace
```
Kafka message arrives on any of 5 topics:
→ KafkaAuditConsumer.consume(message, topic)  [@Transactional]

  Step 1: Parse JSON (with fault tolerance)
    try:
      node = objectMapper.readTree(message)
    catch Exception:
      log.warn("Invalid JSON... Saving raw payload.")
      node = null  ← saves record anyway with null metadata

  Step 2: Extract metadata
    tenantId  = node?.get("tenantId").asText()        or null
    eventType = node?.get("eventType").asText()        or topic  ← fallback to topic name!
    entityId/entityType = first match of callId/agentId/userId    or null

  Step 3: Determine source service
    getSourceService(topic) → "call-service" / "routing-service" / "agent-state-service" / etc.

  Step 4: Build and save
    AuditEvent event = AuditEvent.builder()
        .tenantId(tenantId)          // null if missing
        .eventType(eventType)        // topic name if no eventType field
        .sourceService(sourceService)
        .entityType(entityType)      // CALL / AGENT / USER / null
        .entityId(entityId)          // actual ID / null
        .payloadJson(message)        // FULL raw string, always
        .build()
    auditRepository.save(event)  [PG INSERT: audit_events]

  If INSERT fails: @Transactional rolls back → Kafka does NOT commit offset → message retried
```

### Example: AGENT_DISCONNECTED event through audit
```
Kafka: agent-events
{
  "eventType": "AGENT_DISCONNECTED",
  "agentId": "AG_001",
  "tenantId": "tenant1",
  "previousStatus": "BUSY",
  "newStatus": "OFFLINE",
  "callId": "abc-123",
  "timestamp": "..."
}

→ tenantId  = "tenant1"
→ eventType = "AGENT_DISCONNECTED"  (has eventType field)
→ entityType = "CALL", entityId = "abc-123"  (callId takes priority over agentId)
→ sourceService = "agent-state-service"
→ payloadJson = full JSON string above
→ INSERT into audit_events  [PG]
```

> **Note:** Because `callId` is checked before `agentId`, an AGENT_DISCONNECTED event that includes a `callId` will be recorded with `entityType=CALL`, not `entityType=AGENT`. This is a subtle parsing behavior worth remembering.

