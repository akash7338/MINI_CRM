# FreeSWITCH Reference Guide (MiniGenesys)

This document provides a conceptual and technical overview of how FreeSWITCH is configured and used within the MiniGenesys platform.

---

## 🏗️ Architecture Overview

In MiniGenesys, FreeSWITCH acts purely as a **Media Gateway / Back-to-Back User Agent (B2BUA)**. 

### 🚨 What We Are NOT Building
* **No SIP Users/Extensions:** We do not register softphones, desk phones, or maintain internal extensions.
* **No PBX-style routing:** We do not configure voicemail, ring groups, or IVRs directly inside FreeSWITCH's XML files.

### 📞 What We ARE Building
* **Carrier Integration:** FreeSWITCH connects to external PSTN/SIP Trunk providers (e.g., Sinch, Bandwidth, Telnyx) using SIP.
* **External Control via ESL:** FreeSWITCH intercepts calls, parks them, and notifies our Java `freeswitch-service` via the Event Socket Library (ESL). The Java service then controls the call lifecycle (originate, bridge, record, hangup).

```
   [ PSTN / Phone Network ]
              │
              │ SIP (Signaling) & RTP (Audio Media)
              ▼
    [ FreeSWITCH (Docker) ]
              │
              │ ESL (Event Socket Library TCP Connection)
              ▼
   [ freeswitch-service ]  ◄───►  [ call-service ]
```

---

## 📦 1. Docker Environment (`docker-compose.yml`)

The FreeSWITCH service is containerized using the `bytedesk/freeswitch` image.

### Exposed Ports
* **`5062:5060/udp` & `5062:5060/tcp` (SIP Signaling):** Host port `5062` maps to container port `5060`. This is used to negotiate call setups/teardowns with the PSTN provider.
* **`8022:8021/tcp` (Event Socket / ESL):** Host port `8022` maps to container port `8021`. Our Spring Boot application connects here to send commands and receive events.
* **`16410-16426:16384-16400/udp` (RTP Media):** Maps host port range to container port range. These UDP ports carry the actual voice audio streams.

### Volumes
* **`./conf:/usr/local/freeswitch/etc/freeswitch`:** Mounts our custom XML configurations.
* **`./recordings:/var/lib/freeswitch/recordings`:** Host directory where FreeSWITCH writes `.wav` or `.mp3` call recordings.

---

## ⚙️ 2. Core Configurations

### 📄 `freeswitch.xml`
The root bootstrapper. It includes all configurations, dialplans, and directory resources via preprocessing tags:
* `<section name="configuration">` loads configs from `autoload_configs/*.xml`.
* `<section name="dialplan">` loads dialplans from `dialplan/*.xml`.
* `<section name="directory">` loads directories from `directory/*.xml`.

### 📄 `autoload_configs/modules.conf.xml`
Specifies which modules load at startup. Critical modules for our MVP:
* **`mod_sofia`:** The SIP engine enabling SIP trunks.
* **`mod_event_socket`:** Opens the TCP port for ESL controller connections.
* **`mod_dptools`:** Provides execution applications (e.g., `answer`, `park`, `playback`, `bridge`).
* **`mod_commands`:** Exposes admin APIs (e.g., `originate`, `uuid_kill`, `uuid_record`).
* **`mod_sndfile`:** Handles reading and writing audio files (WAV).

---

## 🔌 3. Event Socket & Access Control

### 📄 `autoload_configs/event_socket.conf.xml`
Configures the ESL listener.
* **`listen-port`**: Set to `8021` (mapped to `8022` on host).
* **`password`**: Set to `ClueCon` (for service authentication).
* **`apply-inbound-acl`**: Set to `"lan"` to allow authorized inbound network connections.

### 📄 `autoload_configs/acl.conf.xml`
Defines network access lists.
* Defines the `"lan"` network list with `default="allow"`. This ensures our Java service running on the host machine (or in a separate container) is permitted to open a TCP socket connection to FreeSWITCH ESL.

---

## ☎️ 4. SIP & Audio Media

### 📄 `autoload_configs/sofia.conf.xml`
Configures Sofia-SIP profiles.
* **`<profile name="external">`**: The SIP profile facing the public internet / carriers.
* **`<gateways>`**: Placeholder section. Later, carrier credentials and SIP gateway parameters for platforms like Sinch/Bandwidth will be placed here to authenticate outbound trunking.

### 📄 `autoload_configs/switch.conf.xml`
Controls core engine parameters, specifically the RTP port range to allocate for voice media:
* `rtp-start-port` = `16384`
* `rtp-end-port` = `16400`
*(Note: These must match the ports exposed in `docker-compose.yml`.)*

---

## 🔀 5. Call Flows & Dialplans

### 📄 `dialplan/public.xml`
Defines what happens when an inbound call hits the simulated external carrier profile.

```xml
<extension name="inbound_mvp">
  <condition field="destination_number" expression="^.*$">
    <action application="answer"/>
    <action application="playback" data="tone_stream://%(2000,4000,440,350)"/>
    <action application="park"/>
  </condition>
</extension>
```

#### Step-by-Step Flow:
1. **`answer`**: FreeSWITCH answers the call, sending a `200 OK` SIP signal back to the carrier.
2. **`playback`**: Plays a ringing or confirmation tone to the caller.
3. **`park`**: Suspends the dialplan execution. The call is kept alive on a hold leg, waiting for an external command from our Java service via ESL to do something (e.g., bridge to an agent, play recording, or hang up).

### 📄 `directory/default.xml`
Used to register SIP users. It contains `<users></users>` with no entries, reflecting our design requirement of **no internal registered extension clients**.

---

## 📊 6. Log Files

* **`autoload_configs/console.conf.xml`**: Determines console logging levels. Visible using `docker logs minigenesys-freeswitch-mvp` or `fs_cli`.
* **`autoload_configs/logfile.conf.xml`**: Writes file logs inside the container to `/var/log/freeswitch/freeswitch.log`.
