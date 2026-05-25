# FreeSWITCH MVP Integration Changelog

Tracks all changes made during the phased FreeSWITCH integration into MiniGenesys.

---

## Phase A — Tenant Entity + Seed Data + AuthResponse telephonyProvider
*2026-05-22 — Isolation Implementation: Phase A*

### What Changed and Why

#### 1. `user-service/model/Tenant.java` [NEW]

A new JPA entity backed by the `tenants` table.

**Why:** `Tenant.telephonyProvider` is the single source of truth for which telephony
provider handles all calls for a tenant. It must not live on the `User` entity (users
belong to tenants, not the other way around). Any future per-tenant config (capacity
limits, recording rules, etc.) belongs here too.

Fields: `id` (String PK, e.g. `"tenant-twilio"`), `name`, `telephony_provider`.

#### 2. `user-service/repository/TenantRepository.java` [NEW]

Standard Spring Data JPA repository. Login uses `findById(user.getTenantId())`.

#### 3. `user-service/resources/data.sql` [NEW]

Seeds two MVP demo tenants on every startup:

| id | name | telephony_provider |
|----|------|--------------------|
| `tenant-twilio` | Acme Corp (Twilio) | `TWILIO` |
| `tenant-freeswitch` | Beta Corp (FreeSWITCH) | `FREESWITCH` |

`ON CONFLICT (id) DO NOTHING` makes it fully idempotent — safe to re-run.

**Why a SQL seed, not Java code:** Keeps tenant configuration out of service logic.
Adding or modifying tenants only requires updating this file, not recompiling.

#### 4. `user-service/resources/application.yml` [MODIFIED]

Added `spring.sql.init.mode: always` so Spring Boot runs `data.sql` on startup even
when `ddl-auto: update` is active (Hibernate schema updates and Spring Boot SQL init
are separate mechanisms).

#### 5. `user-service/dto/AuthResponse.java` [MODIFIED]

Added `telephonyProvider` field (String, e.g. `"TWILIO"` or `"FREESWITCH"`).

**Why a convenience field:** The frontend needs this immediately after login to decide
which SDK to initialize. It avoids a second API call. It is labeled as a convenience
field in the Javadoc — the canonical value is always the `tenants` table.

#### 6. `user-service/service/UserService.java` [MODIFIED]

`login()` now:
1. Loads the `User` by username (unchanged).
2. Verifies the password (unchanged).
3. Looks up `Tenant` by `user.getTenantId()`.
4. **Fails loudly** (HTTP 500 + error log) if the tenant record is missing — a missing
   tenant is a configuration error that must be caught early, not silently defaulted.
5. Logs `user, tenant, provider` on every successful login.
6. Populates `telephonyProvider` in `AuthResponse` from `tenant.getTelephonyProvider()`.

**Key correction applied:** The old DB-miss workaround in `telephony-service` used
`orElse(empty)` to silently skip any call without a matching session — this was fragile
because it would also swallow real Twilio errors. Phase A does NOT change that yet
(Phase D will fix it using the `telephonyProvider` field on `RoutingEvent`).

### Files Changed Summary

| File | Type | Change |
|------|------|--------|
| `user-service/.../model/Tenant.java` | NEW | Tenant entity with telephonyProvider |
| `user-service/.../repository/TenantRepository.java` | NEW | JPA repo for Tenant |
| `user-service/.../resources/data.sql` | NEW | MVP tenant seed data |
| `user-service/.../resources/application.yml` | MODIFIED | Enable data.sql execution |
| `user-service/.../dto/AuthResponse.java` | MODIFIED | Add telephonyProvider field |
| `user-service/.../service/UserService.java` | MODIFIED | Tenant lookup on login |

### Validation Steps

After restarting `user-service`, verify Phase A manually:

**Step 1 — Tenant table is created and seeded:**
```sql
-- Connect to minigenesys_users DB
SELECT id, name, telephony_provider FROM tenants;
-- Expected rows:
-- tenant-twilio     | Acme Corp (Twilio)     | TWILIO
-- tenant-freeswitch | Beta Corp (FreeSWITCH) | FREESWITCH
```

**Step 2 — Login returns telephonyProvider for a Twilio-tenant user:**
```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"<twilio-tenant-user>","password":"<password>"}' | jq .
# Expected: { "telephonyProvider": "TWILIO", "tenantId": "tenant-twilio", ... }
```

**Step 3 — Login returns telephonyProvider for a FreeSWITCH-tenant user:**
```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"<freeswitch-tenant-user>","password":"<password>"}' | jq .
# Expected: { "telephonyProvider": "FREESWITCH", "tenantId": "tenant-freeswitch", ... }
```

**Step 4 — Login fails loudly for a user with no tenant record:**
Temporarily insert a user with `tenant_id = 'nonexistent-tenant'` and attempt login.
Expected: HTTP 500 with message `"Tenant configuration missing. Contact your administrator."`
Expected log: `ERROR Login failed: tenant 'nonexistent-tenant' not found in tenants table.`

**Step 5 — No regression on existing Twilio flow:**
Log in as an existing Twilio agent. Confirm all existing `call`, `routing`, and `telephony`
flows still work. `telephonyProvider` is additive to the response — no existing field changed.

> [!NOTE]
> Phase A only touches `user-service`. No other service was changed.
> Phases B–F (Call entity, Kafka events, consumer isolation, frontend SDK init) are pending approval.

---


## Phase 0 — Architecture Decision
*2026-05-21*

### What was decided

FreeSWITCH logic lives in its own dedicated microservice called **`freeswitch-service`**, separate from the existing `telephony-service` (Twilio).

| Decision | Detail |
|---|---|
| Why a separate service? | Avoids dependency clashes (Netty ESL vs. Twilio SDK) and lets each service have an independent lifecycle |
| Who orchestrates calls? | `call-service` — it stores a `telephonyProvider` field on every `Call` entity |
| Mid-call controls | Frontend calls `call-service` REST endpoints; `call-service` routes to either `telephony-service` (Twilio) or `freeswitch-service` (FreeSWITCH) based on provider |
| Twilio flow | 100% untouched — `telephony-service` still processes `routing-events` from Kafka but silently ignores FreeSWITCH sessions |
| Call session state | Local DB table inside `freeswitch-service` maps FreeSWITCH channel UUIDs to internal call IDs |

### FreeSWITCH Docker Fixes

When running FreeSWITCH in Docker on Mac, several strict NAT traversal settings are required to force FreeSWITCH to communicate externally with WebRTC ICE instead of dropping local LAN candidates.

#### `sofia.conf.xml`

**1. `ndlb-force-ctx-ip`**
Docker masquerades the incoming WSS (WebSockets Secure) TCP connection, making the SIP request appear as if it comes from the local Docker bridge gateway (`172.18.0.1`). FreeSWITCH's core engine detects this and assumes it is a strictly local call, overriding external IPs and substituting `172.18.0.2` in the SDP offer. Setting `ndlb-force-ctx-ip` to `true` disables this destructive fallback, forcing FreeSWITCH to always advertise the `ext-rtp-ip` contact IP.

```xml
<param name="ndlb-force-ctx-ip" value="true"/>
```

**2. `apply-candidate-acl`**
When `ext-rtp-ip` is configured, FreeSWITCH acts as an external endpoint. In this mode, its ICE candidate filter drops any private (RFC1918) candidates by default. Since our browser is on the local LAN (e.g. `192.168.1.4`), FreeSWITCH drops the candidate, resulting in `no suitable candidates found`. We bypass this by explicitly telling the candidate filter to accept the LAN ACL list.

```xml
<param name="apply-candidate-acl" value="lan"/>
```

**3. `ext-rtp-ip`**
Instead of using STUN (which advertises the public ISP IP and breaks local network traversal), we explicitly pass the local Mac IP via Docker Compose environment variable so FreeSWITCH advertises a reachable path.

```xml
<param name="ext-rtp-ip" value="$${FREESWITCH_EXT_IP}"/>
<param name="ext-sip-ip" value="$${FREESWITCH_EXT_IP}"/>
```

#### `docker-compose.yml`

**1. RTP Port Mapping**
FreeSWITCH's `switch.conf.xml` explicitly defines RTP ports `16384` to `16400`. The Docker port mappings must exactly match this range for UDP traffic to traverse into the container.

```yaml
    ports:
      - "16384-16400:16384-16400/udp"
```

**2. FREESWITCH_EXT_IP Environment Variable**
Passes the host's LAN IP to FreeSWITCH.
```yaml
    environment:
      - FREESWITCH_EXT_IP=${FREESWITCH_EXT_IP:-192.168.1.4}
```

---

## Phase 1 — FreeSWITCH Docker & Configuration
*2026-05-21*

### What was built

A minimal, production-ready FreeSWITCH container configuration.

### Key files created

| File | Purpose |
|---|---|
| `docker-compose.yml` | FreeSWITCH container with limited port mapping and volume mounts |
| `freeswitch.xml` | Root XML config loader |
| `vars.xml` | Placeholder global variables |
| `modules.conf.xml` | Reduced module set (only what's needed) |
| `event_socket.conf.xml` | ESL socket binding on port 8021 (mapped to 8022 on host) |
| `switch.conf.xml` | RTP port range limits |
| `sofia.conf.xml` | SIP profile with external gateway placeholders |
| `console.conf.xml` | Console log level config |
| `logfile.conf.xml` | File-based log output |
| `dialplan/public.xml` | Inbound dialplan: answers call and parks it |

### How to validate

```bash
# Container is running and healthy
docker ps

# FreeSWITCH engine is ready
docker exec minigenesys-freeswitch-mvp fs_cli -x "status"

# SIP profile is RUNNING
docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia status"

# No XML parse errors
docker exec minigenesys-freeswitch-mvp fs_cli -x "reloadxml"
```

---

## Phase 2 — `freeswitch-service` Spring Boot Bootstrap
*2026-05-21*

### What was built

A new Spring Boot microservice skeleton at port **8093**.

### Key files created

| File | Purpose |
|---|---|
| `build.gradle` | Gradle build with ESL client dependency |
| `FreeswitchServiceApplication.java` | Spring Boot entry point |
| `FreeswitchHealthController.java` | `GET /health` and `GET /api/v1/freeswitch/health` endpoints |
| `application.yml` | DB, ESL, and Kafka config |

### Database

Created database `minigenesys_freeswitch` to prevent JPA startup failure.

### How to validate

```bash
cd backend/freeswitch-service && ./gradlew bootRun

curl -i http://localhost:8093/health
curl -i http://localhost:8093/api/v1/freeswitch/health
# Both should return: HTTP 200 {"status":"UP"}
```

---

## Phase 3 — ESL Connection & Event Logging
*2026-05-22*

### What was built

`FreeswitchEslService` — connects to FreeSWITCH over ESL (Event Socket Library), subscribes to channel events, and logs them.

### How it works

1. On startup (`@PostConstruct`), schedules a background connection attempt on a daemon thread.
2. If the connection fails, it retries every `retry-interval-seconds` (default 15s) — non-blocking.
3. Once connected, subscribes to: `CHANNEL_CREATE`, `CHANNEL_ANSWER`, `CHANNEL_PARK`, `CHANNEL_BRIDGE`, `CHANNEL_HANGUP_COMPLETE`.
4. Logs each event with: `name`, `uuid`, `caller`, `destination`, `callState`.

### Key files changed

| File | What changed |
|---|---|
| `build.gradle` | Corrected ESL client artifact ID |
| `FreeswitchEslService.java` | Full implementation: background connect with retry, event subscription, event logging |
| `application.yml` | ESL port set to 8022 (host-mapped), added `connect-timeout-seconds`, `retry-interval-seconds` |
| `acl.conf.xml` (Docker) | New ACL policy allowing host-to-container connections |
| `event_socket.conf.xml` (Docker) | Applied named ACL `lan` (was incorrectly using CIDR notation) |

### How to validate

```bash
# Start the service — expect this in logs:
# INFO [esl-connect-thread] : Successfully connected to FreeSWITCH ESL and subscribed to events.
cd backend/freeswitch-service && ./gradlew bootRun

# Originate a test call
docker exec minigenesys-freeswitch-mvp fs_cli -x "load mod_loopback"
docker exec minigenesys-freeswitch-mvp fs_cli -x "originate loopback/1234/public &park"

# Hang it up
docker exec minigenesys-freeswitch-mvp fs_cli -x "uuid_kill <UUID>"

# Expect these events in Spring Boot logs:
# [ESL-EVENT] name=CHANNEL_CREATE uuid=... callState=DOWN
# [ESL-EVENT] name=CHANNEL_ANSWER uuid=... callState=RINGING
# [ESL-EVENT] name=CHANNEL_PARK  uuid=... callState=ACTIVE
# [ESL-EVENT] name=CHANNEL_HANGUP_COMPLETE uuid=...
```

---

## Phase 3 Review — Bugs Found & Fixed
*2026-05-22*

### Bug fixes

| # | File | Problem | Fix |
|---|---|---|---|
| 1 | `FreeswitchEslService` | Stale unused import (`InetSocketAddress`) | Removed |
| 2 | `FreeswitchEslService` | Wrong Caller-ID fallback: `Caller-Screen-Bit` is a boolean flag, not a number | Changed fallback to `Caller-ANI` |
| 3 | `FreeswitchEslService` | ESL connect failure was silently swallowed in `@PostConstruct` — service booted with no ESL connection | Added background retry loop with logging |
| 4 | `FreeswitchEslService` | `client` field was not `volatile` — background thread assigned it but main thread could read stale null on shutdown | Made `volatile` |
| 5 | `FreeswitchEslService` | Redundant `canSend()` check after a blocking `connect()` that throws on failure | Removed |
| 6 | `FreeswitchEslService` | `callState` was missing from event log line | Added |
| 7 | `application.yml` | Explicit `hibernate.dialect` setting was deprecated — caused `HHH90000025` warning | Removed (Hibernate auto-detects) |
| 8 | `application.yml` | Missing `open-in-view: false` caused JPA web warning on every boot | Added |
| 9 | `application.yml` | `show-sql: true` in default profile — SQL noise | Set to `false` |
| 10 | `application.yml` | `agent-dial-mapping` was premature (Phase 5 scope) | Removed with comment |
| 11 | `application.yml` | `call-service-url` was premature (Phase 4 scope) | Removed, re-added properly in Phase 4 |
| 12 | `FreeswitchServiceApplication` | `@EnableKafka` with no listeners yet caused noisy warnings when Kafka is down | Removed |
| 13 | `modules.conf.xml` | `mod_loopback` only loaded dynamically via CLI — disappears on container restart | Added to static config |
| 14 | `event_socket.conf.xml` | `apply-inbound-acl` used CIDR `0.0.0.0/0` — FreeSWITCH requires a named ACL, not CIDR | Changed to `lan` |
| 15 | `sofia.conf.xml` | `ext-sip-ip` / `ext-rtp-ip` set to `auto-linklocal` — link-local 169.254.x.x is wrong for Docker bridge | Changed to `auto` |

### Post-fix validation

```bash
# Restart container and verify mod_loopback loads automatically
docker restart minigenesys-freeswitch-mvp
docker exec minigenesys-freeswitch-mvp fs_cli -x "module_exists mod_loopback"
# Expected: true


---

## [2026-05-22] Audit: Tenant-Level Telephony Isolation

---

### 1. Current State Audit

#### FreeSWITCH: Files/Classes/Configs Added

| Layer | File | Purpose |
|-------|------|---------|
| Docker | `docker/freeswitch/docker-compose.yml` | FreeSWITCH container with SIP (5062), ESL (8022), WSS (7443), WS (5066), RTP ports |
| Config | `docker/freeswitch/conf/freeswitch.xml` | Root config loader |
| Config | `docker/freeswitch/conf/autoload_configs/modules.conf.xml` | Module list (mod_sofia, mod_event_socket, mod_loopback, etc.) |
| Config | `docker/freeswitch/conf/autoload_configs/sofia.conf.xml` | External SIP profile + internal WebRTC profile (blind reg, no auth) |
| Config | `docker/freeswitch/conf/autoload_configs/event_socket.conf.xml` | ESL TCP listener on port 8021 |
| Config | `docker/freeswitch/conf/autoload_configs/acl.conf.xml` | ACL `lan` allow-all for ESL host access |
| Config | `docker/freeswitch/conf/autoload_configs/switch.conf.xml` | RTP port range 16384–16400 |
| Config | `docker/freeswitch/conf/dialplan/public.xml` | Answers + parks all inbound calls |
| Service | `backend/freeswitch-service/` (entire module) | New Spring Boot microservice |
| Java | `FreeswitchServiceApplication.java` | Spring Boot entry point |
| Java | `FreeswitchEslService.java` | ESL connection + event handler (CHANNEL_PARK, CHANNEL_HANGUP_COMPLETE) |
| Java | `FreeswitchCallService.java` | Kafka assignment handler → dials agent via ESL |
| Java | `FreeswitchCallSession.java` | JPA entity tracking customer/agent leg UUIDs and status |
| Java | `FreeswitchCallSessionRepository.java` | DB repository |
| Java | `CallServiceClient.java` | REST client to call-service (createInternalCall, startCall, completeCall) |
| Java | `RoutingEventConsumer.java` (freeswitch) | Kafka consumer on `routing-events` topic (group: `freeswitch-service-group`) |
| Java | `FreeswitchHealthController.java` | Health endpoints |
| Java | `FreeswitchConfig.java` | RestTemplate bean |
| DB | `minigenesys_freeswitch` (Postgres) | Dedicated database for freeswitch-service |
| DB | `freeswitch_call_sessions` (table) | Session state per call (customerUuid, agentUuid, status) |

#### Twilio/telephony-service: Files Changed

| File | Change |
|------|--------|
| `TelephonyService.java` | `handleAssignment()` changed from `orElseThrow()` to `orElse(empty)` + early return with info log, to silently ignore FreeSWITCH call assignments |

#### call-service: Files Changed

**None.** The call-service was NOT modified. It has no awareness of which telephony provider created a call. Its `Call` entity has no `telephonyProvider` field.

#### Frontend: Files Changed

| File | Change |
|------|--------|
| `freeswitch-webrtc.service.ts` | NEW — JsSIP WebRTC client registering to `wss://localhost:7443` as `sip:{agentId}@localhost` |
| `app.component.ts` | MODIFIED — both `telephony.initialize(agentId)` (Twilio) AND `freeswitchWebRtc.initialize(agentId)` are called on every agent login, unconditionally |
| `telephony-overlay.component.ts` | MODIFIED — overlay listens to both `incomingCall$` (Twilio) and `incomingSession$` (FreeSWITCH) simultaneously |
| `package.json` | MODIFIED — added `jssip` dependency |

#### Database/Entity Changes

| Change | Location |
|--------|----------|
| New DB: `minigenesys_freeswitch` | PostgreSQL |
| New table: `freeswitch_call_sessions` | `freeswitch-service` JPA |
| **No changes** to `calls` table in `call-service` | `call-service` has no `telephonyProvider` column |
| **No changes** to `users` table | `user-service` has no `telephonyProvider` field for tenant config |

#### Kafka Topics: Current Consumers/Producers

| Topic | Producer | Consumers (all active simultaneously) |
|-------|----------|---------------------------------------|
| `call-events` | `call-service` | `routing-service` |
| `routing-events` | `routing-service` | `call-service-group`, **`telephony-service-group`**, **`freeswitch-service-group`** ← all 3 consume EVERY event |
| `call-lifecycle-events` | `call-service` | `agent-state-service` |
| `telephony-events` | `telephony-service` | `websocket-gateway` |

#### Where Provider Selection Currently Happens

**Nowhere.** There is no provider selection mechanism in the current system. The selection is accidental:
- `telephony-service` acts on a call only if it finds a `TelephonyCallSession` by `internalCallId` — otherwise it silently skips.
- `freeswitch-service` acts on a call only if it finds a `FreeswitchCallSession` by `internalCallId` — otherwise it silently skips.
- The frontend initializes BOTH Twilio and FreeSWITCH WebRTC clients unconditionally on every agent login.

---

### 2. Problems and Risk Areas

| # | Severity | Area | Problem |
|---|----------|------|---------|
| 1 | 🔴 CRITICAL | Frontend | Both Twilio SDK and JsSIP (FreeSWITCH WebRTC) are initialized simultaneously for every agent login regardless of tenant. Any inbound call triggers BOTH ringing overlays. |
| 2 | 🔴 CRITICAL | Kafka | All 3 services (`call-service`, `telephony-service`, `freeswitch-service`) consume every `routing-events` message. The filtering is purely by database lookup (does a local session exist?), not by tenant provider. This is fragile and race-condition prone. |
| 3 | 🔴 CRITICAL | call-service | The `Call` entity has no `telephonyProvider` field. There is no way to know which provider owns a call from `call-service`'s perspective. |
| 4 | 🟡 HIGH | freeswitch-service `CallServiceClient` | Skills are hardcoded to `["sales"]` and priority to `1` — completely ignoring the actual call attributes. |
| 5 | 🟡 HIGH | Tenant config | There is no tenant-level provider config anywhere (not in DB, env, or feature flags). A tenant cannot be designated as TWILIO or FREESWITCH. |
| 6 | 🟡 HIGH | RoutingEvent DTO | `RoutingEvent` has no `telephonyProvider` field. Provider context is completely absent from the event contract. |
| 7 | 🟠 MEDIUM | telephony-service patch | The FreeSWITCH isolation fix in `TelephonyService` is a workaround (silent skip on DB miss), not a principled isolation. If a Twilio session DB lookup fails for unrelated reasons (timeout, race), it will also silently skip a legitimate Twilio call. |
| 8 | 🟠 MEDIUM | freeswitch-service `defaultTenantId` | The tenant ID defaults to hardcoded `"tenant-123"` if not in the ESL event headers — this will misattribute calls under real multi-tenant conditions. |
| 9 | 🟠 MEDIUM | Frontend login response | `AuthResponse` does not include the tenant's telephony provider. The frontend has no way to know which SDK to load even if it wanted to. |
| 10 | 🟢 LOW | `freeswitch_call_sessions` status flow | `startCall()` requires call to be in `ROUTED` status, but freeswitch-service calls it after bridging — the bridge happens after the `ASSIGNED` event already transitioned the call to `ROUTED` via `call-service`, so this should be OK. Needs verification. |

---

### 3. Recommended Tenant-Level Isolation Design

#### Where Should Tenant Telephony Provider Config Live?

**The `users` table (user-service) and the login response `AuthResponse`.**

Specifically:
- Add a `telephonyProvider` column to the `users` table (`TWILIO` or `FREESWITCH`).
- Alternatively (cleaner for multi-user tenants), add it to a `tenants` table if one exists, or derive it from tenant config.
- At login, `AuthResponse` must include the `telephonyProvider` field.
- Frontend stores it in `localStorage` and uses it to initialize only the correct SDK.

#### How Should Agent Login Know Which Provider to Use?

`UserService.login()` looks up the user, which has a `tenantId`. Add a `tenants` table or a `telephonyProvider` enum column on `User` (tenant-level config stored per tenant). Return it in `AuthResponse`.

#### How Should the Frontend Load Only the Correct SDK?

In `app.component.ts`, instead of calling both `telephony.initialize()` and `freeswitchWebRtc.initialize()` unconditionally:

```ts
const provider = localStorage.getItem('telephonyProvider');
if (provider === 'TWILIO') {
  this.telephony.initialize(agentId).catch(...);
} else if (provider === 'FREESWITCH') {
  this.freeswitchWebRtc.initialize(agentId);
}
```

#### How Should call-service Route Calls Based on Provider?

Add `telephonyProvider` to the `Call` entity and `CreateCallRequest`. When `freeswitch-service` calls `createInternalCall`, it should pass `telephonyProvider: FREESWITCH`. When Twilio creates a call, it passes `telephonyProvider: TWILIO`. The `calls` table then permanently records ownership.

#### How Should routing-events Be Filtered by Provider?

Add `telephonyProvider` to the `RoutingEvent` DTO in `shared-common`. The routing-service populates it from the `Call` entity when publishing. Each consumer then filters by provider:
- `telephony-service`: only processes events where `telephonyProvider == TWILIO`
- `freeswitch-service`: only processes events where `telephonyProvider == FREESWITCH`

This eliminates the fragile "does a DB session exist?" workaround.

#### How Should telephony-service Safely Ignore FreeSWITCH?

With the `telephonyProvider` field in `RoutingEvent`:
```java
if (!"TWILIO".equals(event.getTelephonyProvider())) return;
```

#### How Should freeswitch-service Safely Ignore Twilio?

With the `telephonyProvider` field in `RoutingEvent`:
```java
if (!"FREESWITCH".equals(event.getTelephonyProvider())) return;
```

---

### 4. Phase-by-Phase Fix Plan

#### Phase A: Add Tenant Provider Config
- Add `telephonyProvider` enum column to `users` table (or create a `tenants` table).
- Update `UserService.createAgent()` and `createSupervisor()` to accept and store `telephonyProvider`.
- Update `AuthResponse` DTO to include `telephonyProvider`.
- Update `UserService.login()` to return it.

**Files:** `User.java`, `UserService.java`, `AuthResponse.java`, `CreateAgentRequest.java`

#### Phase B: Propagate Provider Through Call Lifecycle
- Add `telephonyProvider` field to `Call` entity and `CreateCallRequest` DTO.
- Update `CallService.createCall()` to store it.
- Update `CallEvent` and `RoutingEvent` shared DTOs to carry `telephonyProvider`.
- Update `routing-service` to read `telephonyProvider` from the call and include it in `RoutingEvent`.

**Files:** `Call.java`, `CreateCallRequest.java`, `CallEvent.java`, `RoutingEvent.java`, `routing-service` (RoutingEventProducer)

#### Phase C: Fix Backend Consumer Isolation
- `telephony-service` `RoutingEventConsumer`: add `if (!"TWILIO".equals(event.getTelephonyProvider())) return;` — remove the fragile DB-miss workaround.
- `freeswitch-service` `RoutingEventConsumer`: add `if (!"FREESWITCH".equals(event.getTelephonyProvider())) return;`
- `freeswitch-service` `CallServiceClient.createInternalCall()`: pass `telephonyProvider: FREESWITCH` in the request body.

**Files:** `telephony-service/RoutingEventConsumer.java`, `TelephonyService.java`, `freeswitch-service/RoutingEventConsumer.java`, `CallServiceClient.java`

#### Phase D: Fix Frontend SDK Initialization
- After login, store `telephonyProvider` in `localStorage`.
- In `app.component.ts`, only initialize the correct SDK based on `localStorage.getItem('telephonyProvider')`.
- In `telephony-overlay.component.ts`, only subscribe to the correct session stream.

**Files:** `api.service.ts`, `app.component.ts`, `telephony-overlay.component.ts`

#### Phase E: Fix Hardcoded Values in freeswitch-service
- `CallServiceClient.createInternalCall()`: skills hardcoded to `["sales"]` — should pass the actual required skills or configure them.
- `FreeswitchEslService`: `defaultTenantId` hardcoded to `"tenant-123"` — should fail-safe or read from config.

**Files:** `CallServiceClient.java`, `FreeswitchEslService.java`, `application.yml`

#### Phase F: Integration Test & Validation
- Verify that a TWILIO tenant agent never initializes JsSIP.
- Verify that a FREESWITCH tenant agent never initializes Twilio Device.
- Verify that a FreeSWITCH `routing-event` is fully ignored by `telephony-service`.
- Verify that a Twilio `routing-event` is fully ignored by `freeswitch-service`.

---

### 5. Files Likely to Change

| File | Phase | Change |
|------|-------|--------|
| `backend/user-service/.../model/User.java` | A | Add `telephonyProvider` column |
| `backend/user-service/.../service/UserService.java` | A | Return provider in login |
| `backend/user-service/.../dto/AuthResponse.java` | A | Add `telephonyProvider` field |
| `backend/shared-common/.../dto/RoutingEvent.java` | B | Add `telephonyProvider` field |
| `backend/shared-common/.../dto/CallEvent.java` | B | Add `telephonyProvider` field |
| `backend/call-service/.../model/Call.java` | B | Add `telephonyProvider` column |
| `backend/call-service/.../dto/CreateCallRequest.java` | B | Add `telephonyProvider` field |
| `backend/call-service/.../service/CallService.java` | B | Store + propagate provider |
| `backend/routing-service/...` | B | Include provider in RoutingEvent |
| `backend/telephony-service/.../kafka/RoutingEventConsumer.java` | C | Filter by TWILIO only |
| `backend/telephony-service/.../service/TelephonyService.java` | C | Remove DB-miss workaround |
| `backend/freeswitch-service/.../kafka/RoutingEventConsumer.java` | C | Filter by FREESWITCH only |
| `backend/freeswitch-service/.../client/CallServiceClient.java` | C, E | Pass provider + fix hardcoded skills |
| `backend/freeswitch-service/.../service/FreeswitchEslService.java` | E | Fix hardcoded defaultTenantId |
| `minigenesys-dashboard/.../app.component.ts` | D | Conditional SDK init |
| `minigenesys-dashboard/.../services/api.service.ts` | D | Store telephonyProvider from login |
| `minigenesys-dashboard/.../telephony/telephony-overlay.component.ts` | D | Conditional subscription |

---

### 6. Validation Checklist

- [ ] **Tenant config exists**: TWILIO tenant has `telephonyProvider=TWILIO` in DB; FREESWITCH tenant has `FREESWITCH`.
- [ ] **Login response includes provider**: `AuthResponse.telephonyProvider` is returned and stored in `localStorage`.
- [ ] **Frontend: only one SDK initializes per agent**: Confirmed by browser console — no simultaneous Twilio + JsSIP registration.
- [ ] **Call entity tracks provider**: `calls` table has `telephony_provider` column populated correctly.
- [ ] **RoutingEvent carries provider**: Kafka messages on `routing-events` topic include `telephonyProvider` field.
- [ ] **telephony-service ignores FreeSWITCH calls**: Log shows `"Skipping non-TWILIO routing event"` for FreeSWITCH assignments.
- [ ] **freeswitch-service ignores Twilio calls**: Log shows `"Skipping non-FREESWITCH routing event"` for Twilio assignments.
- [ ] **FreeSWITCH call pickup works end-to-end**: Loopback simulation rings browser overlay with correct Caller ID, accept bridges audio, hangup cleans both legs.
- [ ] **Twilio call pickup still works end-to-end**: Existing Twilio flow is completely unbroken for TWILIO tenant agents.

# Start service — should have zero warnings about open-in-view / dialect / @EnableKafka
cd backend/freeswitch-service && ./gradlew bootRun

# ESL connects in background thread (not blocking startup)
# Expected log: INFO [...] [esl-connect-thread] : Successfully connected to FreeSWITCH ESL.
```

---

## Phase 4 — Inbound Call Flow: Park → Assign Agent → Bridge → Hangup
*2026-05-22*

### What was built

End-to-end inbound call handling. When a customer calls in, `freeswitch-service` now:

1. Detects the `CHANNEL_PARK` event on the inbound (customer) leg
2. Creates an internal call record in `call-service` via REST
3. Persists a local `FreeswitchCallSession` in its own DB
4. Listens on Kafka `routing-events` topic for an `ASSIGNED` event from `routing-service`
5. Dials the assigned agent over SIP/WebRTC via `originate` ESL command
6. Detects the agent's `CHANNEL_PARK` event (agent answered, leg is parked)
7. Bridges both legs together with `uuid_bridge`
8. Notifies `call-service` that the call has started
9. On either leg hanging up, kills the other leg and marks the call `COMPLETED` in `call-service`

### New files

| File | Purpose |
|---|---|
| `FreeswitchCallSession` (entity) | DB table `freeswitch_call_sessions` — maps customer UUID ↔ agent UUID ↔ internal call ID with status (`PARKED` → `DIALING_AGENT` → `BRIDGED` → `COMPLETED`) |
| `FreeswitchCallSessionRepository` | JPA repository with `findByAgentUuid` and `findByInternalCallId` queries |
| `FreeswitchCallService` | Handles `ASSIGNED` routing events — pre-generates `agentUuid`, updates session to `DIALING_AGENT`, triggers ESL `originate` |
| `RoutingEventConsumer` | Kafka listener on `routing-events` topic (group: `freeswitch-service-group`). Parses `RoutingEvent`, delegates to `FreeswitchCallService`. Throws on parse failure to trigger DLQ |
| `CallServiceClient` | REST client for `call-service`: `createInternalCall`, `startCall`, `completeCall` |
| `FreeswitchConfig` | Spring `@Configuration` — declares `RestTemplate` bean |

### Changes to existing files

| File | What changed |
|---|---|
| `FreeswitchEslService` | Added `handleChannelPark` (inbound branch: creates session; outbound branch: bridges and calls `startCall`) and `handleChannelHangupComplete` (kills opposite leg, marks COMPLETED, calls `completeCall`). Added `originateCallToAgent` method. Wired `CallServiceClient` and `FreeswitchCallSessionRepository`. |
| `application.yml` | Added `services.callServiceUrl` (defaults to `http://localhost:8087`). Added `freeswitch.tenant-id` default for local testing. Enabled `@EnableKafka` now that `@KafkaListener` is in place. |
| `start-all-services.sh` | Added `freeswitch-service` to the startup sequence with a guard — skips if already running. |

### Call session state machine

```
PARKED  →  DIALING_AGENT  →  BRIDGED  →  COMPLETED
  ↑              ↑               ↑            ↑
CHANNEL_PARK  ASSIGNED       CHANNEL_PARK   CHANNEL_HANGUP
(inbound)    (Kafka event)   (outbound/     (either leg)
                              agent leg)
```

### How to validate

```bash
# 1. Start all services (FreeSWITCH container must be running)
cd backend && ./start-all-services.sh

# 2. Simulate an inbound call parking
docker exec minigenesys-freeswitch-mvp fs_cli -x "originate loopback/1234/public &park"

# Expect in freeswitch-service logs:
# Creating internal call for inbound FreeSWITCH call. tenantId=..., caller=...
# Created FreeSWITCH call session: customerUuid=..., internalCallId=...

# 3. Simulate routing-service assigning an agent (publish ASSIGNED event to Kafka)
# freeswitch-service will pick it up and log:
# Dialing WebRTC agent <agentId> with agentUuid <uuid> for customerUuid <uuid>

# 4. When agent answers (CHANNEL_PARK on outbound leg):
# Expect: Bridging customerUuid ... and agentUuid ...
# Expect: Successfully started call in call-service for internalCallId: ...

# 5. Hang up either leg
docker exec minigenesys-freeswitch-mvp fs_cli -x "uuid_kill <customerUuid or agentUuid>"

# Expect:
# Customer/Agent hung up. Terminating [other] leg: ...
# Successfully completed call in call-service for internalCallId: ...
```

---

## Security Hardening — ACL & ESL Safety
*2026-05-22 — Security Improvement*

### What was Unsafe
1. The default network list named `lan` in `acl.conf.xml` was configured with `default="allow"`. This allowed all network traffic to bypass list validation by default.
2. The Event Socket configuration `event_socket.conf.xml` used `apply-inbound-acl` with a raw CIDR value of `0.0.0.0/0`. This was syntactically incorrect for the parameter (which expects a named ACL) and left the ESL socket wide open on all network interfaces.

### What Changed
1. **`acl.conf.xml`**: Updated the `lan` network list to `default="deny"`. Added explicit allow rules for localhost (`127.0.0.1/32`) and private network subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) to support secure Docker-to-host and container-to-container communication.
2. **`event_socket.conf.xml`**: Changed the `apply-inbound-acl` parameter to reference the ACL by name (`"lan"`) rather than a raw CIDR.

### How to Validate
1. Restart the FreeSWITCH container to load new configs:
   ```bash
   docker-compose restart
   ```
2. Verify that XML loaded successfully via `fs_cli`:
   ```bash
   docker exec minigenesys-freeswitch-mvp fs_cli -x "reloadxml"
   ```
3. Check the logs of `freeswitch-service` and confirm the inbound ESL client successfully connects to port 8022:
   ```
   INFO [...] [esl-connect-thread] : Successfully connected to FreeSWITCH ESL and subscribed to events.
   ```

---

## Active Call Retry/Requeue Logic Refinement
*2026-05-25 — Reliability & Telephony Sync Improvement*

### What retry logic was wrong
Previously, when an agent disconnected (closed browser, WebRTC disconnected, or agent leg hung up), the `agent-state-service` published an `AGENT_DISCONNECTED` event. The `call-service` listened to this event, found any call in `ROUTED` or `IN_PROGRESS` state assigned to that agent, and automatically requeued it by resetting the call status to `QUEUED` and republishing a new `CallEvent` to Kafka.

### Why requeueing Java objects is unsafe after telephony disconnect
If a call is already active/bridged (`IN_PROGRESS`), the telephony customer leg and the agent leg are bridged. If the agent leg hangs up or disconnects, the real telephony call on FreeSWITCH or Twilio is torn down or already disconnected. Requeueing only the Java `CallRequest`/`Call` object and assigning it to another agent is incorrect and unsafe because there is no longer a live customer call leg to bridge. This leaves the next assigned agent stuck waiting for media that will never arrive.

### What behavior replaced it
1. **Case-by-Case Disconnect Handling**:
   - **Pre-answer / Waiting Call (`ROUTED` status)**: If a call is assigned but not yet bridged, the customer leg is still waiting in queue/parking. Requeueing is valid. The call is reset to `QUEUED`, cleared of its assigned agent, and a `CALL_REQUEUED` event is published to re-route it to a different agent.
   - **Active/Bridged Call (`IN_PROGRESS` status)**: If the call has already been answered and bridged, we skip requeueing. Instead, we mark the call status as `FAILED` (with reason `"Agent disconnected during active call."`) and publish a `CALL_COMPLETED` lifecycle event to clean up agent states.
2. **Ignored Transitions for OFFLINE Agents**:
   - In `AgentStateService.handleCallCompletion()`, a check was added to ensure that if an agent is already `OFFLINE` (which is their status after a disconnect detection), receiving a `CALL_COMPLETED` event will not transition them back to `AVAILABLE`. They will safely remain `OFFLINE`.
3. **Graceful Early Return for Terminal Calls**:
   - In `CallService.completeCall()`, if a call is already in a terminal state (`COMPLETED`, `FAILED`, `ABANDONED`), the method returns early and gracefully instead of throwing a `CONFLICT` exception. This avoids error logs when late hangup callbacks are received from FreeSWITCH/Twilio for already cleaned-up calls.

### Files Changed
- **`CallService.java`**: Implemented case separation in `handleAgentDisconnect` and early exit for terminal states in `completeCall`.
- **`AgentStateService.java`**: Added safety check in `handleCallCompletion` to ignore completion transitions for `OFFLINE` agents.


---

## Inbound Dialplan & Frontend Presence Desynchronization Fixes
*2026-05-25 — Ringing & Presence Bug Fixes*

### 1. Inbound Dialplan Blockage (Ringing Sound)
- **Problem**: When `mod_tone_stream` was loaded, the inbound dialplan action `<action application="playback" data="tone_stream://%(1000,0,800)"/>` played a tone stream indefinitely, blocking the call from reaching the `park` application. As a result, the call never reached the routing service or the agent.
- **Fix**: Commented out the blocking `playback` action in `public.xml`. The customer leg now immediately parks and is routed to the conference.
- **Ringback Implementation**: Enabled a standard US ringback tone in the conference room using `moh-sound` in `conference.conf.xml`:
  `<param name="moh-sound" value="tone_stream://%(2000,4000,440,480)"/>`
  This plays a ringing sound to the caller while they wait inside the conference room for the agent to answer the WebRTC call.

### 2. Frontend Status Desynchronization on Call Rejection
- **Problem**: When an agent rejected a call, the frontend overlay logged out the agent to set their status to `Offline` (so they wouldn't immediately get routed the same call again). However, when the backend processed the rejection, it sent a `CALL_COMPLETED` lifecycle event. The frontend received this event and unconditionally changed the agent UI status to `Ready`, causing a mismatch since the backend database and Redis still had the agent as `OFFLINE`. The desynchronized agent couldn't receive any more calls.
- **Fix**: Updated `session-state.service.ts` to only transition the agent status to `Ready` on `CALL_COMPLETED` if the agent's current status is not `Offline`. Now, after rejecting a call, the agent correctly remains `Offline` in the UI and can log back in by clicking "Start Shift".

### Files Changed
- **`public.xml`**: Commented out the blocking playback tone stream.
- **`session-state.service.ts`**: Added presence guard to the `CALL_COMPLETED` WebSocket handler.

---

## Current Status

| Phase | Status | Description |
|---|---|---|
| Phase 0 | ✅ Done | Architecture decision — dedicated `freeswitch-service` |
| Phase 1 | ✅ Done | FreeSWITCH Docker container + XML config |
| Phase 2 | ✅ Done | `freeswitch-service` Spring Boot bootstrap |
| Phase 3 | ✅ Done | ESL connection with background retry and event logging |
| Phase 4 | ✅ Done | Inbound call: park → route → bridge → hangup propagation |
| Security | ✅ Done | ACL & ESL safety hardening (default-deny policy) |
| Retry Logic | ✅ Done | Active-call retry/requeue logic safely removed and refined |
| Phase 5 | 🔜 Next | Mid-call controls: hold, resume, recording, disconnect REST endpoints |
| Phase 6 | 🔜 Next | Agent outbound dialing (click-to-call from frontend) |
| Phase 7 | 🔜 Next | Integration tests and end-to-end validation |

