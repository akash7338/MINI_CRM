# FreeSWITCH MVP Integration Changelog

This document tracks all changes, configurations, and validations made throughout the phased integration of the FreeSWITCH MVP into MiniGenesys.

---

## [2026-05-21T23:26:46+05:30] Phase 0: Separate freeswitch-service Architecture Decision

### Files Changed
* [implementation_plan.md](file:///Users/akash.singh/.gemini/antigravity-ide/brain/dc39c176-2585-4e41-8963-0e7c51cc1362/implementation_plan.md) (Design / Planning)
* [task.md](file:///Users/akash.singh/.gemini/antigravity-ide/brain/dc39c176-2585-4e41-8963-0e7c51cc1362/task.md) (Task List Update)

### Design Decisions Summary
1. **FreeSWITCH Logic Location**: Inside a dedicated new microservice called `freeswitch-service`.
2. **Microservice Isolation**: Implemented as a separate service to avoid dependency clashes (Netty ESL vs. Twilio SDK) and maintain independent lifetimes.
3. **Call Service Interaction**:
   - `call-service` orchestrates calls and stores a `telephonyProvider` field on the `Call` entity.
   - For mid-call controls, the frontend invokes `call-service` REST endpoints. `call-service` routes them dynamically via HTTP REST to either `telephony-service` (Twilio) or `freeswitch-service` (FreeSWITCH).
4. **Untouched Twilio Flow**:
   - Existing Twilio webhook routes and classes in `telephony-service` are kept 100% untouched.
   - `telephony-service` continues to process `routing-events` via Kafka but silently ignores FreeSWITCH call sessions.
5. **Contracts/Events**:
   - Schema updates in `call-service` to store the call provider.
   - Local DB session mapping table inside `freeswitch-service`.
   - Mid-call control REST endpoints (`hold`, `resume`, `record/start`, `record/stop`, `disconnect`).
6. **Roadmap**: Phased execution starting with FreeSWITCH Docker setup, then `freeswitch-service` bootstrap, ESL connection, inbound call, agent outbound dialing, bridging, hangups, recording, hold/resume, and integration tests.

## [2026-05-21T23:40:00+05:30] Phase 1: FreeSWITCH Docker & Configuration MVP

### Files Changed / Added
* [docker/freeswitch/docker-compose.yml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/docker-compose.yml) (FreeSWITCH Docker configuration with limited port mapping and volume mounts)
* [docker/freeswitch/conf/freeswitch.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/freeswitch.xml) (Main XML configuration loader)
* [docker/freeswitch/conf/vars.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/vars.xml) (Placeholder vars config)
* [docker/freeswitch/conf/autoload_configs/modules.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/modules.conf.xml) (Reduced module load set)
* [docker/freeswitch/conf/autoload_configs/event_socket.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/event_socket.conf.xml) (ESL socket binding config)
* [docker/freeswitch/conf/autoload_configs/switch.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/switch.conf.xml) (RTP start/end port limits)
* [docker/freeswitch/conf/autoload_configs/sofia.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/sofia.conf.xml) (SIP endpoint profile with external gateway placeholders)
* [docker/freeswitch/conf/autoload_configs/console.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/console.conf.xml) (Console logger level mapping)
* [docker/freeswitch/conf/autoload_configs/logfile.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/logfile.conf.xml) (File logger output target)
* [docker/freeswitch/conf/dialplan/public.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/dialplan/public.xml) (Dialplan that answers and parks inbound calls)

### How to Validate
1. **Container Health check**: Run `docker ps` to verify that the `minigenesys-freeswitch-mvp` container is up and healthy.
2. **FreeSWITCH Status**: Run `docker exec minigenesys-freeswitch-mvp fs_cli -x "status"` to verify that FreeSWITCH engine is ready.
3. **Sofia Status**: Run `docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia status"` to verify that the `external` profile is RUNNING.
4. **Dialplan Check**: Run `docker exec minigenesys-freeswitch-mvp fs_cli -x "dialplan xml"` or check dialplan load.
5. **Config XML Parse**: Run `docker exec minigenesys-freeswitch-mvp fs_cli -x "reloadxml"` to verify there are no XML parse warnings or failures.

## [2026-05-21T23:51:00+05:30] Phase 2: Create new `freeswitch-service` Spring Boot bootstrap project

### Files Changed / Added
* [backend/freeswitch-service/gradlew](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/gradlew) (Gradle wrapper script copied from shared-common)
* [backend/freeswitch-service/gradlew.bat](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/gradlew.bat) (Gradle wrapper Windows bat script)
* [backend/freeswitch-service/gradle/](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/gradle/) (Gradle wrapper jar and properties folder)
* [backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/controller/FreeswitchHealthController.java](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/controller/FreeswitchHealthController.java) (New REST controller for health endpoints `/health` and `/api/v1/freeswitch/health`)

### Database Setup
* Created database `minigenesys_freeswitch` to prevent database connection failures on JPA startup.

### How to Run / Validate
1. **Run the service**: Navigate to `backend/freeswitch-service/` and start the application using:
   ```bash
   ./gradlew bootRun
   ```
2. **Check App Health**: Request the health check endpoints:
   ```bash
   curl -i http://localhost:8093/health
   curl -i http://localhost:8093/api/v1/freeswitch/health
   ```
   Verify that both respond with `HTTP/1.1 200` and the payload `{"status":"UP"}`.

## [2026-05-22T00:10:00+05:30] Phase 3: Establish ESL connection and log FreeSWITCH events

### Files Changed / Added
* [backend/freeswitch-service/build.gradle](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/build.gradle) (Updated ESL client dependency coordinate to correct jar artifactId)
* [backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/service/FreeswitchEslService.java](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/service/FreeswitchEslService.java) (Corrected imports, connection method parameters, and close method calls)
* [backend/freeswitch-service/src/main/resources/application.yml](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/resources/application.yml) (Set default ESL port to 8022 matching mapped container port)
* [docker/freeswitch/conf/autoload_configs/acl.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/acl.conf.xml) (New ACL policy that permits host-to-container connections)
* [docker/freeswitch/conf/autoload_configs/event_socket.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/event_socket.conf.xml) (Applied custom inbound ACL `lan` to event socket settings)

### How to Validate
1. **Build and Run**: Start the service inside `backend/freeswitch-service/`:
   ```bash
   ./gradlew bootRun
   ```
   Verify that it outputs: `Successfully connected to FreeSWITCH ESL.` in the console logs.
2. **Simulate a Call**: Load the loopback channel module and originate a test loopback call in FreeSWITCH CLI:
   ```bash
   docker exec minigenesys-freeswitch-mvp fs_cli -x "load mod_loopback"
   docker exec minigenesys-freeswitch-mvp fs_cli -x "originate loopback/1234/public &park"
   ```
3. **Check Event Logs**: Verify that the Spring Boot service captures and logs the following events:
   - `CHANNEL_CREATE`
   - `CHANNEL_ANSWER`
   - `CHANNEL_PARK`
4. **Simulate a Hangup**: Terminate the call channel using:
   ```bash
   docker exec minigenesys-freeswitch-mvp fs_cli -x "uuid_kill <UUID>"
   ```
   Verify that the Spring Boot logs capture the termination:
   - `CHANNEL_HANGUP_COMPLETE`

---

## [2026-05-22T00:35:00+05:30] Phase 3 Review: Issues Found & Fixed

### Issues Found

| # | Severity | Location | Issue |
|---|----------|----------|-------|
| 1 | **Bug** | `FreeswitchEslService.java` | Stale unused import `java.net.InetSocketAddress` leftover from incorrect `connect()` call |
| 2 | **Bug** | `FreeswitchEslService.java` | Wrong Caller-ID fallback field: `Caller-Screen-Bit` is a boolean flag (`true`/`false`), not a phone number. Was producing garbage caller data |
| 3 | **Bug** | `FreeswitchEslService.java` | Silent startup failure: `@PostConstruct` caught ESL connection exception silently; service booted successfully with no active ESL connection and no retry |
| 4 | **Bug** | `FreeswitchEslService.java` | `client` field was non-volatile; ESL thread assigned it but main thread could see stale null on `@PreDestroy` |
| 5 | **Quality** | `FreeswitchEslService.java` | Redundant `canSend()` check after a blocking `connect()` — if connect fails it throws, not returns false; caused confusion |
| 6 | **Quality** | `FreeswitchEslService.java` | Missing `callState` field in event log line; needed for Phase 4 routing decisions |
| 7 | **Config** | `application.yml` | Deprecated `hibernate.dialect` explicit setting — Hibernate auto-detects PostgreSQL; caused HHH90000025 warning on every boot |
| 8 | **Config** | `application.yml` | Missing `spring.jpa.open-in-view: false` — caused JpaWebConfiguration open-in-view warning on every boot |
| 9 | **Config** | `application.yml` | `show-sql: true` in default profile — verbose SQL noise for future phases with entities |
| 10 | **Scope creep** | `application.yml` | `freeswitch.agent-dial-mapping` belongs to Phase 5; premature presence creates scope confusion |
| 11 | **Scope creep** | `application.yml` | `services.call-service-url` belongs to Phase 4; premature presence |
| 12 | **Spring** | `FreeswitchServiceApplication.java` | `@EnableKafka` premature — no `@KafkaListener` exists in Phase 3; caused noisy warnings when Kafka is down |
| 13 | **FreeSWITCH** | `modules.conf.xml` | `mod_loopback` was NOT in static config — only loaded dynamically via `fs_cli` during testing; would disappear on container restart |
| 14 | **FreeSWITCH** | `event_socket.conf.xml` | `apply-inbound-acl` value was `0.0.0.0/0` (CIDR notation) — FreeSWITCH requires a named ACL list name, not a CIDR; worked by accident only |
| 15 | **FreeSWITCH** | `sofia.conf.xml` | `ext-sip-ip` and `ext-rtp-ip` set to `auto-linklocal` — link-local addresses (169.254.x.x) are wrong for Docker bridge networking |

### Files Fixed

| File | Change |
|------|--------|
| `backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/service/FreeswitchEslService.java` | Removed unused import, fixed Caller-ID fallback to `Caller-ANI`, added background retry on ESL connect failure, made `client` volatile, removed redundant `canSend()`, added `callState` to log line, proper daemon thread + interrupt-safe shutdown |
| `backend/freeswitch-service/src/main/resources/application.yml` | Removed deprecated dialect, added `open-in-view: false`, set `show-sql: false`, removed premature `agent-dial-mapping` and `call-service-url`, added `connect-timeout-seconds` and `retry-interval-seconds` ESL config keys |
| `backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/FreeswitchServiceApplication.java` | Removed premature `@EnableKafka` annotation |
| `docker/freeswitch/conf/autoload_configs/modules.conf.xml` | Added `mod_loopback` to static module load list |
| `docker/freeswitch/conf/autoload_configs/event_socket.conf.xml` | Fixed `apply-inbound-acl` from CIDR `0.0.0.0/0` to named ACL list `lan`; fixed indentation |
| `docker/freeswitch/conf/autoload_configs/sofia.conf.xml` | Changed `ext-sip-ip` and `ext-rtp-ip` from `auto-linklocal` to `auto` |

### No changes to
- `call-service/` — untouched
- `telephony-service/` — untouched
- `docker/freeswitch/conf/dialplan/public.xml` — still correct, no changes needed
- `docker/freeswitch/conf/autoload_configs/acl.conf.xml` — still correct

### How to Validate After Fix
1. Restart FreeSWITCH container:
   ```bash
   docker restart minigenesys-freeswitch-mvp
   ```
2. Verify `mod_loopback` loads automatically without manual `fs_cli` command:
   ```bash
   docker exec minigenesys-freeswitch-mvp fs_cli -x "module_exists mod_loopback"
   # Expected: true
   ```
3. Start the service:
   ```bash
   cd backend/freeswitch-service && ./gradlew bootRun
   ```
   Verify **no warnings** about: open-in-view, PostgreSQL dialect, @EnableKafka
4. Verify ESL connects in background thread (not blocking main thread):
   ```
   INFO [...] [-connect-thread] c.m.f.service.FreeswitchEslService : Successfully connected to FreeSWITCH ESL and subscribed to events.
   ```
5. Verify event log includes `callState` field:
   ```bash
   docker exec minigenesys-freeswitch-mvp fs_cli -x "originate loopback/1234/public &park"
   ```
   Expected log:
   ```
   [ESL-EVENT] name=CHANNEL_CREATE uuid=... caller=... destination=1234 callState=DOWN
   [ESL-EVENT] name=CHANNEL_ANSWER uuid=... caller=... destination=1234 callState=RINGING
   [ESL-EVENT] name=CHANNEL_PARK uuid=... caller=... destination=1234 callState=ACTIVE
   ```
