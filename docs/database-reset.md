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
