# FreeSWITCH Debugging & Learning Reference Guide

This document serves as a comprehensive operational reference for managing, debugging, and verifying the FreeSWITCH Back-to-Back User Agent (B2BUA) integration inside the MiniGenesys platform.

---

## 1. Docker & Container Inspection

### Check Container Port Mappings and Health
* **Command**: 
  ```bash
  docker ps -a --filter name=minigenesys-freeswitch-mvp
  ```
* **Purpose**: Verify that the FreeSWITCH container is running, healthy, and exposing the correct host port mappings (SIP, WSS, ESL, RTP).
* **Issue Investigated**: ESL clients throwing connection timeouts, or browser SIP UAs failing to reach the WebSocket port.
* **Discovery**: Confirmed that WSS port `7443`, SIP WebSocket port `5066`, ESL port `8022` (mapping to internal `8021`), and SIP UDP port `5062` were active.
* **Follow-up / Fix**: Standardized ports in `docker-compose.yml` to prevent conflict with native system services.

---

## 2. FreeSWITCH Startup & Debugging

### Fetch Engine Core Status
* **Command**: 
  ```bash
  docker exec minigenesys-freeswitch-mvp fs_cli -x "status"
  ```
* **Purpose**: Retrieve general server uptime, session counts, active calls, and max session configuration.
* **Issue Investigated**: Performance monitoring and verifying the server is fully booted and accepting commands.
* **Discovery**: FreeSWITCH boots up in a lightweight configuration, processing requests in milliseconds.
* **Follow-up / Fix**: Kept the module configuration minimal (`modules.conf.xml`) to keep startup times under 1 second and resource usage low.

---

## 3. ESL (Event Socket Library) Connectivity

### Netcat ESL Port Check
* **Command**: 
  ```bash
  nc -zv localhost 8022
  ```
* **Purpose**: Perform a raw TCP handshake validation with the Event Socket port from the host.
* **Issue Investigated**: `FreeswitchEslService` startup logs showing ESL connection timeouts or Refused Connection exceptions.
* **Discovery**: Determined if the network port was blocked, bound to the wrong interface, or if FreeSWITCH was not listening.
* **Follow-up / Fix**: Ensured the host port `8022` was correctly mapped to the container's `8021` port.

### View Active ESL Messages (Interactive)
* **Command**: 
  ```bash
  docker exec -it minigenesys-freeswitch-mvp fs_cli
  # Inside fs_cli:
  /event plain ALL
  ```
* **Purpose**: Enter the interactive FreeSWITCH CLI and subscribe to all events to watch raw JSON/plain text events stream in real-time.
* **Issue Investigated**: Verifying whether FreeSWITCH is firing expected events (`CHANNEL_PARK`, `CHANNEL_ANSWER`, `CHANNEL_BRIDGE`) when calls progress.
* **Discovery**: Allowed tracing of exact header structures (e.g. `Unique-ID`, `Call-Direction`, `Channel-Call-State`) needed by Java code to deserialize event payloads.
* **Follow-up / Fix**: Structured Java event handlers in [FreeswitchEslService.java](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/service/FreeswitchEslService.java) to dynamically filter incoming event headers.

---

## 4. Sofia SIP Status & Troubleshooting

### Display Sofia Profile Status
* **Command**: 
  ```bash
  docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia status"
  ```
* **Purpose**: List all active SIP profiles (e.g., `internal`, `external`) and verify their listening ports and running state.
* **Issue Investigated**: Checking if the SIP engine started successfully and bound to the configured interfaces.
* **Discovery**: Confirmed that the `internal` profile was running and binding to ports `5060` (SIP) and `5066` (WS).
* **Follow-up / Fix**: Modified XML profiles to load only necessary profiles, keeping configurations clean.

---

## 5. XML Reload & Configuration Verification

### Live Configuration Reload
* **Command**: 
  ```bash
  docker exec minigenesys-freeswitch-mvp fs_cli -x "reloadxml"
  ```
* **Purpose**: Force FreeSWITCH to re-read all XML configuration files from the disk (dialplan, modules, conference profiles) without restarting the container.
* **Issue Investigated**: Changes to `public.xml` or `modules.conf.xml` not reflecting in the call routing behavior.
* **Discovery**: FreeSWITCH parses the configuration instantly, validating XML syntax.
* **Follow-up / Fix**: Used `reloadxml` dynamically after editing dialplans (e.g. adding the `agent_ans` extension) to immediately test new logic.

---

## 6. ACL (Access Control Lists) & Network Security

### Check Loaded Modules for ESL Security
* **Command**: 
  ```bash
  docker exec minigenesys-freeswitch-mvp fs_cli -x "reload acl"
  ```
* **Purpose**: Reload ACL list files to apply changes in permitted IP lists.
* **Issue Investigated**: ESL connections from the host JVM being accepted TCP-wise, but immediately dropped with authorization errors.
* **Discovery**: Docker containers see the host machine routing via the bridge interface IP (e.g., `172.x.x.x` or `192.x.x.x`). The default ACL rules only authorized `127.0.0.1`.
* **Follow-up / Fix**: 
  * Created [acl.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/acl.conf.xml) with:
    ```xml
    <list name="lan" default="allow"/>
    ```
  * Updated [event_socket.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/event_socket.conf.xml) to apply the ACL:
    ```xml
    <param name="apply-acl" value="lan"/>
    ```

---

## 7. WebRTC & SIP Registration Debugging

### List Currently Registered SIP / WebRTC Clients
* **Command**: 
  ```bash
  docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia status profile internal reg"
  ```
* **Purpose**: Inspect currently registered WebRTC endpoints (JSSIP clients in the browser).
* **Issue Investigated**: Agent dashboard WebRTC initialization failing or showing disconnected state.
* **Discovery**: WebSockets was connecting, but SIP registrations were failing due to browser blocking self-signed certificates on port `7443` (WSS).
* **Follow-up / Fix**: Directed users to visit `https://localhost:7443` directly in the browser and choose **Proceed to localhost (unsafe)** to whitelist the self-signed TLS cert.

---

## 8. RTP & Media Flow Debugging

### Check Active Media Channels
* **Command**: 
  ```bash
  docker exec minigenesys-freeswitch-mvp fs_cli -x "show channels"
  ```
* **Purpose**: List all active audio channels in the system, detailing their codecs, sample rates, IP addresses, and state.
* **Issue Investigated**: Calls immediately hanging up after answer, or one-way audio issues.
* **Discovery**: Inspected whether media paths (RTP ports) matched the mapped Docker container ports.
* **Follow-up / Fix**: Checked that `L16` (16-bit Linear PCM) or `PCMU/PCMA` codecs were properly loaded and that RTP port range `16410-16426` was declared in `docker-compose.yml` to prevent media packet drops.

---

## 9. Conference Module Debugging

### Verify mod_conference Status
* **Command**: 
  ```bash
  docker exec minigenesys-freeswitch-mvp fs_cli -x "module_exists mod_conference"
  ```
* **Purpose**: Verify if the conference bridging engine is loaded and operational.
* **Issue Investigated**: Migrating call bridging from direct `uuid_bridge` to conference-based orchestration.
* **Discovery**: Allowed checking of module status directly from CLI (returns `true` if active).
* **Follow-up / Fix**: Added `<load module="mod_conference"/>` in [modules.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/modules.conf.xml) and created [conference.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/conference.conf.xml).

### List Active Conferences and Participants
* **Command**: 
  ```bash
  docker exec minigenesys-freeswitch-mvp fs_cli -x "conference list"
  ```
* **Purpose**: List all active conference rooms and their detailed member states (hear/speak flags, UUIDs, channel names).
* **Issue Investigated**: Confirming that both the customer and agent leg have successfully entered the same room and are bridged.
* **Discovery**: Confirmed that customer loopback leg (`loopback/1234-b`) and mock agent loopback leg (`loopback/agent_ans-a`) were connected as members of the same conference room:
  ```
  Conference 36029213-c617-4599-bbd2-ef69451155c7 (2 members rate: 8000 flags: running|...)
  4;loopback/agent_ans-a;c1ea3f3d-683a-4b3c-b111-39363208b1de;Outbound Call;public;hear|speak|floor;0;0;300
  3;loopback/1234-b;36029213-c617-4599-bbd2-ef69451155c7;;0000000000;hear|speak;0;0;300
  ```
* **Follow-up / Fix**: Validated the migration from standard `uuid_bridge` to conference room bridging.

---

## 10. Call Control & Simulation Commands

### Originate Simulated Inbound Call
* **Command**: 
  ```bash
  docker exec minigenesys-freeswitch-mvp fs_cli -x "originate loopback/1234/public &park"
  ```
* **Purpose**: Spin up a mock inbound call entering the public context at destination `1234`, and park it.
* **Issue Investigated**: End-to-end routing validation without requiring a physical SIP phone registration.
* **Discovery**: Simulated an inbound trunk channel creation, answering, playing Welcome ringback, and generating ESL `CHANNEL_PARK` event payload.
* **Follow-up / Fix**: Used to trigger full Kafka assignment pipeline and verify loopback agent matching.

### Kill Active Channel
* **Command**: 
  ```bash
  docker exec minigenesys-freeswitch-mvp fs_cli -x "uuid_kill <channel_uuid>"
  ```
* **Purpose**: Terminate a specific call leg/channel immediately.
* **Issue Investigated**: Cleaning up orphaned channels or simulating customer hangup behavior.
* **Discovery**: Fired `CHANNEL_HANGUP_COMPLETE` event in ESL, triggering clean session completion.
* **Follow-up / Fix**: Used extensively in manual E2E test runs to clear the active calls and return mock agents back to `AVAILABLE`.
