# Analytics Service

## 1. One-Line Purpose

Aggregates real-time operational metrics (call counts, agent statuses, queue depth) by consuming all Kafka events and maintaining a single metrics row per tenant in PostgreSQL.

---

## 2. When This Service Comes Into Picture

The analytics service runs **passively in the background** at all times. It never blocks any user action or system flow. It simply listens to every Kafka event and updates counters:

- A call is created → `totalCalls++`, `queuedCalls++`
- A call is assigned → `routedCalls++`, `queuedCalls--`
- A call is abandoned → `abandonedCalls++`, `queuedCalls--`
- A call is completed → `completedCalls++`
- An agent goes AVAILABLE → `activeAgents++`, `offlineAgents--`
- The dashboard polls for metrics → `GET /analytics/{tenantId}/metrics`

---

## 3. Responsibilities

1. **Event-Driven Counter Updates** — Consumes 4 Kafka topics and increments/decrements the appropriate counters
2. **Metrics Query API** — Exposes a simple GET endpoint for the dashboard to fetch current metrics
3. **Agent Count Tracking** — Maintains counts of agents by status (AVAILABLE, BUSY, OFFLINE) based on state transition events
4. **Duplicate-Aware Counting** — Uses the `isNew` flag from `call-events` to avoid double-counting requeued calls

---

## 4. APIs Exposed

| Endpoint | Method | Purpose |
|---|---|---|
| `GET /api/v1/analytics/{tenantId}/metrics` | GET | Returns all metrics for a tenant |

### Response Example
```json
{
  "tenantId": "tenant1",
  "totalCalls": 150,
  "queuedCalls": 3,
  "routedCalls": 140,
  "completedCalls": 135,
  "abandonedCalls": 2,
  "noAgentEvents": 15,
  "activeAgents": 4,
  "busyAgents": 2,
  "offlineAgents": 1,
  "averageWaitTimeMs": 12500.0,
  "waitTimeCount": 120,
  "updatedAt": "2026-05-01T08:10:21Z"
}
```

---

## 5. Kafka Usage

### Consumes ← 4 Topics

| Topic | Event | Counter Action |
|---|---|---|
| `call-events` | New call (`isNew: true`) | `totalCalls++`, `queuedCalls++` |
| `call-events` | Requeued call (`isNew: false`) | `queuedCalls++` only (no double-count on totalCalls) |
| `routing-events` | `ASSIGNED` | `routedCalls++`, `queuedCalls--` |
| `routing-events` | `NO_AGENT` | `noAgentEvents++` |
| `routing-events` | `ABANDONED` | `abandonedCalls++`, `queuedCalls--` |
| `agent-events` | Any state change | `updateAgentCounts(tenant, previousStatus, newStatus)` |
| `call-lifecycle-events` | `CALL_COMPLETED` | `completedCalls++` |

**Consumer Group:** `analytics-service-group`

### Does NOT Produce
The analytics service is a pure consumer — it never publishes events to any Kafka topic.

---

## 6. Redis Usage

**None.** Despite the system blueprint mentioning Redis counters, the actual implementation uses PostgreSQL with `@Version` optimistic locking. This is a conscious simplification — PostgreSQL provides durability and the `@Transactional` wrapper ensures counter consistency.

---

## 7. PostgreSQL Usage

### Database: `minigenesys_analytics`

### Table: `tenant_metrics`
| Column | Type | Description |
|---|---|---|
| `tenant_id` | VARCHAR (PK) | One row per tenant |
| `version` | BIGINT | Optimistic locking (`@Version`) |
| `total_calls` | BIGINT | Total calls ever created |
| `queued_calls` | BIGINT | Currently queued calls (incremented/decremented) |
| `routed_calls` | BIGINT | Total calls successfully routed |
| `completed_calls` | BIGINT | Total calls completed |
| `abandoned_calls` | BIGINT | Total calls abandoned (max retries) |
| `no_agent_events` | BIGINT | Total NO_AGENT routing failures |
| `active_agents` | BIGINT | Currently AVAILABLE agents |
| `busy_agents` | BIGINT | Currently BUSY agents |
| `offline_agents` | BIGINT | Currently OFFLINE agents |
| `average_wait_time_ms` | DOUBLE | Rolling average wait time |
| `wait_time_count` | BIGINT | Number of data points for the rolling average |
| `updated_at` | TIMESTAMP | Last update time |

### Design Decision: Single Row Per Tenant
Instead of maintaining individual metric records per event, the service uses a **single row per tenant** that is continuously updated. This makes reads extremely fast (single row lookup by PK) and avoids the complexity of time-series aggregation.

### Optimistic Locking
The `@Version` column prevents lost updates when multiple Kafka consumer threads update the same tenant's metrics simultaneously. If two threads read the same version, the second one's save will fail and Spring will retry the transaction.

---

## 8. Important State Changes

The analytics service doesn't have its own state machine. It mirrors the state of other services via counters:

### Counter Dependencies
```
totalCalls   = count of all call-events where isNew=true
queuedCalls  = currently waiting calls (goes up and down)
routedCalls  = count of ASSIGNED routing events
completedCalls = count of CALL_COMPLETED lifecycle events
abandonedCalls = count of ABANDONED routing events
```

### Known Drift Risk
If events are processed out of order (e.g., `ASSIGNED` arrives before the `call-events` for a requeued call), `queuedCalls` might temporarily go negative. The code uses `Math.max(0, count - 1)` to prevent negative values.

---

## 9. Interaction With Other Services

| Direction | Service | How | Why |
|---|---|---|---|
| **Consumes ←** | All producing services | Kafka (4 topics) | Aggregate metrics from every event |
| **Called by ←** | API Gateway | HTTP proxy | Dashboard fetches metrics |

The analytics service is **completely isolated** — it never calls any other service. It only reads from Kafka and writes to its own database.

---

## 10. Edge Cases / Failure Scenarios

| Scenario | What Happens |
|---|---|
| **Tenant has no metrics row yet** | `getMetrics()` returns a default object with all zeros. `updateMetric()` creates the row on first update |
| **Duplicate events from Kafka** | Counters will be double-incremented. There is no deduplication in the analytics service (it trades accuracy for simplicity) |
| **Events arrive out of order** | `queuedCalls` may temporarily show incorrect values. `Math.max(0, ...)` prevents negative counts |
| **Optimistic lock conflict** | Two threads update the same tenant → one gets `OptimisticLockException` → Spring retries the transaction |
| **The ping-pong bug** | Before the routing fix, the repeated ASSIGNED/DISCONNECTED cycle would inflate `routedCalls` and `noAgentEvents` counters significantly |

---

## 11. Interview Explanation

> "The analytics service is a passive event aggregator. It consumes from four Kafka topics — call-events, routing-events, agent-events, and call-lifecycle-events — and maintains a single metrics row per tenant in PostgreSQL. For example, when a call-event arrives with `isNew: true`, it increments totalCalls and queuedCalls. When a routing-event with ASSIGNED arrives, it increments routedCalls and decrements queuedCalls. The dashboard polls this service to show real-time counters. I used `@Version` optimistic locking to handle concurrent updates from multiple Kafka consumer threads without deadlocks. The trade-off is that counters can drift if events arrive out of order or are duplicated, but for a dashboard showing approximate real-time metrics, this is acceptable."
