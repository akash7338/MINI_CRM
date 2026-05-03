# Database Reset Commands

Run the following commands in your terminal to completely wipe all active calls, queues, temporary agent assignments, and telemetry data. This ensures a 100% clean state for testing, while keeping the essential `admin` and `kumar_akash14` users intact.

### 1. Flush Redis (In-Memory Queues)
This connects to your running Redis Docker container (`funny_napier`) and deletes all keys, removing all queued calls and rate-limiting data.
```bash
docker exec funny_napier redis-cli flushall
```

### 2. Truncate Call Service Database
This wipes all call records and their associated required skills.
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_call_service -c "TRUNCATE TABLE calls CASCADE;"
```

### 3. Truncate Telephony Service Database
This wipes all Twilio session mappings (SID to Call ID).
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_telephony -c "TRUNCATE TABLE telephony_call_sessions CASCADE;"
```

### 4. Truncate Routing Service Database
This wipes all routing assignment history.
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_routing -c "TRUNCATE TABLE assignments CASCADE;"
```

### 5. Truncate Analytics Service Database
This wipes all the recorded telemetry data.
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_analytics -c "TRUNCATE TABLE tenant_metrics CASCADE;"
```

### 6. Clean Agent State Database
This removes any ghost agents that were dynamically created during tests, keeping only the primary `AG_001` agent.
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_agent_state -c "DELETE FROM agent_skills WHERE agent_id NOT IN ('AG_001'); DELETE FROM agents WHERE id NOT IN ('AG_001'); UPDATE agents SET status = 'AVAILABLE', active_call_id = NULL WHERE id = 'AG_001';"
```

### 7. Clean Users Database
This deletes any test users that might have been registered, keeping only the primary accounts you need to log in.
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_users -c "DELETE FROM users WHERE username NOT IN ('admin', 'kumar_akash14');"
```

---

### All-In-One Command
If you want to do this all in one go, you can copy and paste this entire block into your terminal:

```bash
docker exec funny_napier redis-cli flushall && \
PGPASSWORD=postgres psql -U postgres -d minigenesys_call_service -c "TRUNCATE TABLE calls CASCADE;" && \
PGPASSWORD=postgres psql -U postgres -d minigenesys_telephony -c "TRUNCATE TABLE telephony_call_sessions CASCADE;" && \
PGPASSWORD=postgres psql -U postgres -d minigenesys_routing -c "TRUNCATE TABLE assignments CASCADE;" && \
PGPASSWORD=postgres psql -U postgres -d minigenesys_analytics -c "TRUNCATE TABLE tenant_metrics CASCADE;" && \
PGPASSWORD=postgres psql -U postgres -d minigenesys_agent_state -c "DELETE FROM agent_skills WHERE agent_id NOT IN ('AG_001'); DELETE FROM agents WHERE id NOT IN ('AG_001'); UPDATE agents SET status = 'AVAILABLE', active_call_id = NULL WHERE id = 'AG_001';" && \
PGPASSWORD=postgres psql -U postgres -d minigenesys_users -c "DELETE FROM users WHERE username NOT IN ('admin', 'kumar_akash14');"
```

---

## Inspection Commands
Use these commands to verify the data and state of your system.

### 1. Inspect Redis (Hot State)
Check what's currently happening in the routing engine and agent presence.

```bash
# List all keys related to a specific tenant
docker exec funny_napier redis-cli KEYS "tenant:*"

# Check the state of a specific agent (e.g., AG_001)
docker exec funny_napier redis-cli HGETALL "tenant:T001:agent:AG_001:state"

# View the current call queue
docker exec funny_napier redis-cli ZRANGE "tenant:T001:call:queue" 0 -1 WITHSCORES

# View available agents for a specific skill (e.g., English)
docker exec funny_napier redis-cli ZRANGE "tenant:T001:skill:English:available" 0 -1 WITHSCORES
```

### 2. Inspect Postgres (Persistence)
Check the records in each microservice database.

**Call Service (Active and History):**
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_call_service -c "SELECT id, status, assigned_agent_id FROM calls ORDER BY created_at DESC LIMIT 5;"
```

**Agent State Service (Presence):**
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_agent_state -c "SELECT id, name, status, active_call_id FROM agents;"
```

**Telephony Service (Twilio Sessions):**
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_telephony -c "SELECT * FROM telephony_call_sessions LIMIT 5;"
```

**User Service (Accounts):**
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_users -c "SELECT id, username, role, tenant_id FROM users;"
```

**Routing Service (Assignments):**
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_routing -c "SELECT call_id, agent_id, status FROM assignments ORDER BY assigned_at DESC LIMIT 5;"
```

**Audit Service (Event Logs):**
```bash
PGPASSWORD=postgres psql -U postgres -d minigenesys_audit -c "SELECT event_type, tenant_id, created_at FROM audit_events ORDER BY created_at DESC LIMIT 10;"
```

### 3. Fetch Table Details (Schema)
If you need to see the columns and data types for any table.

```bash
# Example: Check columns in the 'agents' table
PGPASSWORD=postgres psql -U postgres -d minigenesys_agent_state -c "\d agents"

# Example: Check columns in the 'calls' table
PGPASSWORD=postgres psql -U postgres -d minigenesys_call_service -c "\d calls"
```
