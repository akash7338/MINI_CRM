# Walkthrough: Twilio-like FreeSWITCH B2BUA Bridging MVP

This document summarizes the simplified, Twilio-mimicking FreeSWITCH integration design for MiniGenesys.

## What Was Completed

1. **B2BUA Design Mapping**: Formulated an architecture where FreeSWITCH acts as a Back-to-Back User Agent (B2BUA), bridging calls directly via `uuid_bridge` or originate bridging (`&bridge(...)`), completely avoiding SIP users registry, agent directory setups, and WebRTC.
2. **Minimal Configuration Definition**: Defined 4 minimal configuration files required to run FreeSWITCH in Docker.
3. **Core Call Flows**: Map ESL actions directly to Twilio-like functions:
   - Wait prompt playback upon customer call park.
   - Channel bridging upon agent assignment.
   - `uuid_broadcast` for Hold/Resume audio injection.
   - `uuid_record` for localized MP3 session recording.
   - `uuid_kill` for call teardown.

## Phase 1: FreeSWITCH Docker & Configuration Setup

* **Docker Container**: Created and started a lightweight FreeSWITCH instance running in Docker (`minigenesys-freeswitch-mvp`) configured to load custom configuration directories.
* **SIP & RTP Binding**: Bound external host port `5062` to SIP `5060`, and mapped RTP ports `16410-16426`.
* **ESL Configuration**: Enabled Event Socket Library (ESL) listening on port `8021` (mapped to host port `8022`) with standard password `ClueCon`.
* **Dialplan & Gateways**: Implemented a public dialplan context (`public`) that automatically answers any incoming call, plays a welcome tone, and parks it. Sofia gateway configuration is stubbed out for carrier/PSTN provider integrations.

### Verification
* Docker container `minigenesys-freeswitch-mvp` successfully runs on macOS.
* FreeSWITCH is online and responsive:
  ```bash
  docker exec minigenesys-freeswitch-mvp fs_cli -x "status"
  docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia status"
  docker exec minigenesys-freeswitch-mvp fs_cli -x "reloadxml"
  ```

## Phase 2: Create new `freeswitch-service` Spring Boot bootstrap project

* **Gradle Setup**: Copied the Gradle wrapper binaries (`gradlew`, `gradlew.bat`, `gradle/`) from `shared-common` to `freeswitch-service` to enable standalone compiling.
* **Database Creation**: Created the local PostgreSQL database `minigenesys_freeswitch` to avoid hibernate entity-manager connection exceptions during service initialization.
* **Health Endpoint Controller**: Created [FreeswitchHealthController.java](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/controller/FreeswitchHealthController.java) implementing GET endpoints `/health` and `/api/v1/freeswitch/health`.
* **Esl Configuration Placeholders**: Verified config placeholders inside [application.yml](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/resources/application.yml) targeting the correct ESL properties: `FREESWITCH_ESL_HOST`, `FREESWITCH_ESL_PORT`, `FREESWITCH_ESL_PASSWORD`.

### Verification
* Running `./gradlew bootRun` starts the application successfully on port `8093`.
* Both health endpoints return status `UP`:
  ```bash
  curl -i http://localhost:8093/health
  curl -i http://localhost:8093/api/v1/freeswitch/health
  ```
