# MiniGenesys: Complete FreeSWITCH, Networking & Architecture Guide

> **This is the single source of truth for everything FreeSWITCH, networking, WebRTC, ESL, NAT, Docker, and call flows in the MiniGenesys platform.**
> All other scattered FreeSWITCH docs have been consolidated here.

---

# Table of Contents

1. **Chapter 1: Architecture & Core Philosophy**
   - 1.1 What We Are Building (MiniGenesys Philosophy)
   - 1.2 FreeSWITCH Core and Modules
   - 1.3 Module Configurations (`modules.conf.xml`)
   - 1.4 XML Configuration Structure (`freeswitch.xml` & Pre-Processing)
   - 1.5 XML Sections (Config, Dialplan, Directory)
   - 1.6 Custom App Design (Why the Directory is Empty)

2. **Chapter 2: Docker Environment & Port Mappings**
   - 2.1 Docker Compose Port Mappings Explained
   - 2.2 Volume Mounts
   - 2.3 The Three Worlds of Addresses (Foundation Concept)
   - 2.4 Why Telnyx Cannot Reach 192.168.1.4 or 172.18.0.2

3. **Chapter 3: Command & Control (Event Socket Library - ESL)**
   - 3.1 ESL Core Concept & Architecture
   - 3.2 ESL Connection Setup in Java (Startup Sequence)
   - 3.3 Docker Port Mapping for Control Sockets
   - 3.4 ESL vs. Sofia Profiles (Command vs. Execution)
   - 3.5 The Real Flow (Java App -> ESL -> FreeSWITCH)

4. **Chapter 4: Telephony & Browser WebRTC Integration**
   - 4.1 SIP (Signaling) vs. RTP (Media)
   - 4.2 Sofia Profile Telephony Roles (Internal vs. External Profiles)
   - 4.3 Dialplan Contexts & Call Routing (`public.xml`)
   - 4.4 WebRTC Browser Softphones & Blind Registration
   - 4.5 SDP (Session Description Protocol) Payload Negotiation
   - 4.6 Understanding `sip-ip` vs `ext-sip-ip` vs `rtp-ip` vs `ext-rtp-ip`
   - 4.7 Why `0.0.0.0` is Used for Listening but Not for Advertising

5. **Chapter 5: The Network Layer: NAT, Docker & IP Traversal**
   - 5.1 Local LAN IP vs. Router Gateway IP vs. Public WAN IP
   - 5.2 How Your System Gets Its IP (DHCP Handshake Step-by-Step)
   - 5.3 Mapping Logical IPs to Physical Hardware (ARP Protocol)
   - 5.4 How Docker NAT Works (Apartment Lobby Analogy)
   - 5.5 Why Standard Web NAT Works (NAT Logbook / State Table)
   - 5.6 Why NAT Breaks VoIP/WebRTC (The SDP Payload Problem)
   - 5.7 ICE, STUN & TURN: Traversing NAT for WebRTC Media

6. **Chapter 6: End-to-End Inbound Call Flow**
   - 6.1 Complete Packet-Level Call Lifecycle Diagram
   - 6.2 Step 1: Inbound Call Arrives from Carrier (Telnyx → FreeSWITCH)
   - 6.3 Step 2: ESL Event Arrives at Java (FreeSWITCH → freeswitch-service)
   - 6.4 Step 3: Session Created & call-service Notified
   - 6.5 Step 4 & 5: Routing via Kafka (call-service → routing-service → Kafka)
   - 6.6 Step 6: freeswitch-service Receives Routing Event
   - 6.7 Step 7: Customer Transferred to Conference + Agent Dialed
   - 6.8 Step 8: Agent Answers (CHANNEL_ANSWER)
   - 6.9 Step 9: Hangup & Cleanup (CHANNEL_HANGUP_COMPLETE)

7. **Chapter 7: FreeSWITCH Configuration Reference**
   - 7.1 `sofia.conf.xml` Key Parameters Explained
   - 7.2 `event_socket.conf.xml` & ACL Security
   - 7.3 `switch.conf.xml` RTP Port Range
   - 7.4 `vars.xml` Global Variables

8. **Chapter 8: ACL & Network Security**
   - 8.1 What ACLs Do
   - 8.2 `acl.conf.xml` Configuration
   - 8.3 `event_socket.conf.xml` Security Hardening

9. **Chapter 9: Debugging Reference**
   - 9.1 Docker & Container Inspection
   - 9.2 FreeSWITCH Engine Status
   - 9.3 ESL Connectivity
   - 9.4 Sofia SIP Status & Gateway Verification
   - 9.5 WebRTC / SIP Registration Debugging
   - 9.6 RTP & Media Flow Debugging
   - 9.7 Conference Module Debugging
   - 9.8 Call Control & Simulation Commands

10. **Chapter 10: Known Bugs, Root Causes & Fixes**
    - 10.1 Calls Not Ringing the Agent Browser
    - 10.2 Wrong Dial String for WebRTC Registrations (`%localhost`)
    - 10.3 ICE Failure — FreeSWITCH Advertising Docker IP in SDP
    - 10.4 Frontend Status Desync on Call Rejection
    - 10.5 Dialplan Blocking on `tone_stream` Loop
    - 10.6 Common Failure Cases & Diagnosis Guide

11. **Chapter 11: MVP Integration Changelog**
    - 11.1 Phase 0: Architecture Decision
    - 11.2 Phase 1: FreeSWITCH Docker & Configuration
    - 11.3 Phase 2: `freeswitch-service` Spring Boot Bootstrap
    - 11.4 Phase 3: ESL Connection & Event Logging
    - 11.5 Phase 4: Inbound Call Flow (Park → Route → Bridge → Hangup)
    - 11.6 Security Hardening
    - 11.7 Retry/Requeue Logic Refinement
    - 11.8 Tenant-Level Isolation Audit & Fix Plan (Phases A–F)
    - 11.9 Current Status

---

# Chapter 1: Architecture & Core Philosophy

### 1.1 What We Are Building (MiniGenesys Philosophy)
FreeSWITCH behaves purely as a **programmable Media Gateway / Back-to-Back User Agent (B2BUA)**, NOT a traditional office PBX system.

```text
MiniGenesys Dashboard (Browser)
          |
  Java Orchestration (freeswitch-service)
          | (ESL Commands)
      FreeSWITCH (Docker Container)
          | (SIP Trunk)
    PSTN Provider (Telnyx)
```

- **What we ARE building:** A Twilio-style programmable call engine, a PSTN bridge, and an API-controlled call center router.
- **What we are NOT building:** A standard office PBX system with desk-to-desk extensions, voicemail boxes, or user directory setups.
- **What we are NOT doing:** Registering physical SIP phones, configuring ring groups, or using IVRs inside FreeSWITCH XML.

---

### 1.2 FreeSWITCH Core and Modules
FreeSWITCH is constructed using a modular plugin architecture:

```text
       +---------------------------------------------+
       |               FreeSWITCH Core               |
       |  (Thread Mgmt, Audio Pipes, XML Parser)      |
       +---------------------------------------------+
            |               |               |
      +-----+-----+   +-----+-----+   +-----+-----+
      | mod_sofia |   | mod_event |   | mod_dptools|
      |   (SIP/   |   |  _socket  |   | (Dialplan |
      |  WebRTC)  |   |   (ESL)   |   |  Tools)   |
      +-----------+   +-----------+   +-----------+
```

| Module | Core Purpose |
| :--- | :--- |
| **`mod_sofia`** | The SIP engine. Handles SIP signaling, WebRTC, and RTP audio. |
| **`mod_event_socket`** | Opens a TCP control socket (ESL) for remote apps (Java) to command FreeSWITCH. |
| **`mod_dptools`** | Provides dialplan actions (`answer`, `park`, `bridge`, `playback`). |
| **`mod_commands`** | Standard console command suite (`uuid_kill`, `originate`, `reloadxml`). |
| **`mod_sndfile`** | Decodes/encodes audio files (`.wav`, `.mp3`). |
| **`mod_opus`** | Opus codec support — required for browser WebRTC audio. |
| **`mod_conference`** | Conference bridge engine — used to bridge customer + agent into a shared room. |
| **`mod_loopback`** | Creates virtual loopback calls for testing without a real SIP phone. |

---

### 1.3 Module Configurations (`modules.conf.xml`)
This file tells the FreeSWITCH engine which modules to load at boot:
```xml
<load module="mod_sofia"/>
<load module="mod_event_socket"/>
<load module="mod_opus"/>
<load module="mod_conference"/>
```
If a module is not listed here, its features are entirely unavailable—even if its config file exists.

---

### 1.4 XML Configuration Structure (`freeswitch.xml` & Pre-Processing)
The root file `freeswitch.xml` pulls in all configs using pre-processor directives before FreeSWITCH fully starts:

```xml
<X-PRE-PROCESS cmd="include" data="autoload_configs/*.xml"/>
<X-PRE-PROCESS cmd="include" data="dialplan/*.xml"/>
```

`X-PRE-PROCESS` with `cmd="set"` defines global variables used throughout all config files:
```xml
<X-PRE-PROCESS cmd="set" data="external_rtp_ip=192.168.1.4"/>
```

---

### 1.5 XML Sections (Config, Dialplan, Directory)

```xml
<section name="configuration"> <!-- Module configurations (autoload_configs/) -->
<section name="directory">     <!-- SIP users/extensions (directory/) -->
<section name="dialplan">      <!-- Call routing logic (dialplan/) -->
```

---

### 1.6 Custom App Design (Why the Directory is Empty)
In a standard PBX, you create XML accounts in the directory section for each phone (user `1001`, password `secret`).

Because we are building an ESL-orchestrated platform:
- We set `accept-blind-reg=true` and `auth-calls=false` in the Sofia profile.
- This means: *"Accept WebRTC registrations without looking up passwords in the directory."*
- JsSIP in the browser dynamically registers as `sip:agent-id@localhost` on-the-fly.

---

# Chapter 2: Docker Environment & Port Mappings

### 2.1 Docker Compose Port Mappings Explained

```yaml
ports:
  - "5062:5060/udp"        # SIP External Profile (Carrier Inbound)
  - "5062:5060/tcp"        # SIP External Profile (Carrier Inbound)
  - "7443:7443/tcp"        # WSS - WebRTC Browser Signaling
  - "5066:5066/tcp"        # WS  - Insecure WebSocket (local testing)
  - "8022:8021/tcp"        # ESL - Java Control Socket
  - "16384-16400:16384-16400/udp"  # RTP Media Ports (Voice Audio)
```

Every mapping reads as `hostPort:containerPort`:
- **Left side (host port):** The port that opens on your Mac at `192.168.1.4`. This is where Docker listens on the Mac.
- **Right side (container port):** The port FreeSWITCH listens on inside its virtual Docker network at `172.18.0.2`.

A packet arriving at your Mac on port `5062` is **automatically forwarded by Docker** to the container at `172.18.0.2:5060`. Your Java code, your browser, and your router all use the **left-side host ports** — they never need to know the container's internal port.

---

### 2.2 Volume Mounts
```yaml
volumes:
  - ./conf:/usr/local/freeswitch/etc/freeswitch    # Our XML configs
  - ./recordings:/var/lib/freeswitch/recordings     # Call recordings
```

---

### 2.3 The Three Worlds of Addresses (Foundation Concept)

Every source of confusion about Docker + VoIP + NAT comes from mixing up these three completely separate address spaces:

```text
+-----------------------------------------------------------------------+
|  WORLD 3: Public Internet                                             |
|  ● Your Router's Public WAN IP: 203.0.113.5                          |
|  ● Telnyx's IP:                 148.64.x.x                           |
|  ● Anyone on the internet can reach these addresses.                  |
|                                                                       |
|  +-----------------------------------------------------------------+  |
|  |  WORLD 2: Your Local Wi-Fi (LAN)                               |  |
|  |  ● Your Mac's LAN IP:        192.168.1.4                       |  |
|  |  ● Your Router's LAN IP:     192.168.1.1                       |  |
|  |  ● Only home Wi-Fi devices can reach 192.168.1.4               |  |
|  |  ● Telnyx CANNOT reach 192.168.1.4                             |  |
|  |                                                                 |  |
|  |  +-----------------------------------------------------------+  |  |
|  |  |  WORLD 1: Docker Virtual Network                          |  |  |
|  |  |  ● FreeSWITCH container IP:  172.18.0.2                  |  |  |
|  |  |  ● Docker bridge gateway:    172.18.0.1                  |  |  |
|  |  |  ● Only Docker containers can reach 172.18.0.2           |  |  |
|  |  |  ● Telnyx DEFINITELY cannot reach 172.18.0.2            |  |  |
|  |  +-----------------------------------------------------------+  |  |
|  +-----------------------------------------------------------------+  |
+-----------------------------------------------------------------------+
```

**The Complete Address Reference Table:**

| Address | What it is | Who can reach it |
| :--- | :--- | :--- |
| `203.0.113.5` | Your Airtel router's **Public WAN IP** | Anyone on the internet (Telnyx) |
| `192.168.1.1` | Your router's **LAN-side gateway IP** | Home Wi-Fi devices only |
| `192.168.1.4` | Your **Mac's LAN IP** (assigned by DHCP) | Home Wi-Fi devices only |
| `172.18.0.1` | Docker's **virtual bridge gateway** | Docker containers only |
| `172.18.0.2` | **FreeSWITCH's private container IP** | Docker containers only |

---

### 2.4 Why Telnyx Cannot Reach 192.168.1.4 or 172.18.0.2

- **`192.168.1.4` is a private RFC 1918 address.** The entire `192.168.x.x` block is reserved for local networks. Internet routers are configured to drop packets destined for these addresses.
- **`172.18.0.2` is a Docker virtual address.** It only exists inside the virtual bridge network running on your Mac. Even if Telnyx somehow sent a packet to `172.18.0.2`, no router on the internet would know where to forward it.
- **The only reachable address is `203.0.113.5`** (your router's public WAN IP). Telnyx must be configured to send SIP packets to this address. Your router then uses **port forwarding** to relay packets to your Mac, and Docker forwards them into the container.

---

# Chapter 3: Command & Control (Event Socket Library - ESL)

### 3.1 ESL Core Concept & Architecture
ESL (Event Socket Library) is the remote-control interface of FreeSWITCH, managed by `mod_event_socket`. It opens a persistent TCP server.

```text
[ Java Spring Boot App ] --- TCP port 8022 ---> [ Mac Host ]
                                                      |
                                              Docker Port Forward
                                                      |
                                          [ FreeSWITCH mod_event_socket :8021 ]
```

Two core capabilities:
1. **Send Commands:** `originate`, `uuid_bridge`, `uuid_kill`, `uuid_record`.
2. **Receive Events:** `CHANNEL_CREATE`, `CHANNEL_PARK`, `CHANNEL_ANSWER`, `CHANNEL_HANGUP_COMPLETE`.

---

### 3.2 ESL Connection Setup in Java (Startup Sequence)

When `freeswitch-service` Spring Boot starts, `@PostConstruct` triggers a background connection:

```java
// Step 1: Create empty client object (no connection yet)
Client newClient = new Client();

// Step 2: Register event callback BEFORE connecting (never miss an event)
newClient.addEventListener(new IEslEventListener() {
    public void eventReceived(EslEvent event) {
        handleEvent(event);  // every FreeSWITCH event lands here
    }
});

// Step 3: Open TCP connection and authenticate
// Internally: TCP connect → FS sends "auth/request" → library sends "auth ClueCon" → "+OK accepted"
newClient.connect(eslHost, eslPort, eslPassword, connectTimeoutSeconds);

// Step 4: Subscribe to specific event types
// Sends: "event plain CHANNEL_CREATE CHANNEL_ANSWER CHANNEL_PARK CHANNEL_BRIDGE CHANNEL_HANGUP_COMPLETE"
newClient.setEventSubscriptions("plain", EVENT_SUBSCRIPTIONS);
```

Without `setEventSubscriptions()`, FreeSWITCH pushes **no events at all** even though the TCP connection is open.

If `connect()` fails (e.g. Docker container isn't ready), the catch block retries every 15 seconds. This handles the startup race condition where the Java service boots before FreeSWITCH is fully initialized.

---

### 3.3 Docker Port Mapping for Control Sockets
```yaml
ports:
  - "8022:8021/tcp"
```
- Java app connects to `localhost:8022` on the Mac host.
- Docker translates this to `172.18.0.2:8021` inside the container.
- `listen-ip=0.0.0.0` in `event_socket.conf.xml` ensures FreeSWITCH binds to all container interfaces, accepting connections forwarded from Docker.

---

### 3.4 ESL vs. Sofia Profiles (Command vs. Execution)

| Concept | Layer | Core Responsibility |
| :--- | :--- | :--- |
| **ESL (`mod_event_socket`)** | Control Plane | Orchestration. Tells FreeSWITCH *what* to do based on business logic. Does NOT handle audio. |
| **Sofia (`mod_sofia`)** | Signaling/Media Plane | Telephony. Establishes SIP routes, manages WebRTC connections, and routes RTP audio. |

---

### 3.5 The Real Flow (Java App → ESL → FreeSWITCH)

```text
Java App sends:
  originate {origination_uuid=def-456}sofia/internal/akash-freeswitch%localhost &conference(abc-123@default)

FreeSWITCH's mod_sofia executes:
  - Sends SIP INVITE over WSS to browser at wss://192.168.1.4:7443
  - Browser answers, SDP/ICE negotiated
  - Agent audio channel created
  - Agent dropped into conference room abc-123@default
  - Customer already in room abc-123@default
  - Both hear each other
```

---

# Chapter 4: Telephony & Browser WebRTC Integration

### 4.1 SIP (Signaling) vs. RTP (Media)
VoIP is divided into two completely separate protocols — this separation is the root cause of most NAT problems:

1. **SIP (Session Initiation Protocol):** Handles call signaling only (dialing, ringing, answering, hanging up). Runs over TCP/UDP port `5060` or secure WSS port `7443`. **SIP carries no audio.**
2. **RTP (Real-time Transport Protocol):** Carries digitized voice audio. Runs over high-range UDP ports (e.g., `16384` to `16400`).

Because of this separation:
- You can have a working SIP connection (signaling) but complete silence (media failure).
- If a call connects but drops exactly 8–10 seconds after answering, it is almost always a **Media/ICE/NAT failure**, not a signaling failure.

---

### 4.2 Sofia Profile Telephony Roles (Internal vs. External Profiles)
The terms `internal` and `external` in FreeSWITCH refer to **telephony roles**, NOT physical network positions. 

Think of FreeSWITCH as a middleman sitting between your **Agents (Browser)** and the **Outside World (Telnyx)**.

#### Summary of Ports & Profiles

| Port | Profile | Target Audience | Protocol | What it does |
| :--- | :--- | :--- | :--- | :--- |
| **`7443`** | `internal` | Agent Browser | SIP over WSS (WebSockets) | Agents connect here to log in (register). FreeSWITCH uses this persistent WebSocket to push inbound calls to the browser. |
| **`5060` (mapped to `5062`)** | `external` | Telnyx / PSTN | Raw SIP over UDP/TCP | FreeSWITCH reaches out here to register with Telnyx. Telnyx sends inbound customer SIP `INVITE` packets here. |

#### Visual Flow
* **External Profile (port 5062 → 5060):**
  Faces the **public carrier/PSTN side**. Receives inbound SIP INVITEs from Telnyx/Twilio.
  ```text
  Telnyx/Customer PSTN → [ External Profile (SIP UDP 5060) ] → public Dialplan
  ```

* **Internal Profile (port 7443 WSS):**
  Faces the **agent browser side**. Handles WebRTC signaling from JsSIP.
  ```text
  Agent Browser (JsSIP) → [ Internal Profile (WSS 7443) ] → default Dialplan
  ```

---

### 4.3 Dialplan Contexts & Call Routing (`public.xml`)
When a carrier sends a SIP INVITE, the external profile routes it to the `public` dialplan context:

```xml
<extension name="inbound_pstn">
  <condition field="destination_number" expression="^(.*)$">
    <action application="answer"/>     <!-- Accept the call, establish RTP -->
    <action application="park"/>       <!-- Hold call, fire CHANNEL_PARK event -->
  </condition>
</extension>
```

| Action | What it does |
| :--- | :--- |
| `answer` | Sends SIP `200 OK` back to carrier. Establishes the RTP media channel. |
| `park` | Puts call into a waiting hold state. Fires `CHANNEL_PARK` event to ESL. |

When `park` executes, Java takes control via ESL. The dialplan's job is done.

> **Note:** An earlier version had `<action application="playback" data="tone_stream://%(1000,0,800)"/>` between answer and park. This blocked the dialplan indefinitely because `tone_stream` loops forever with no terminator. It was removed. Ringback is now handled by `moh-sound` in the conference configuration.

---

### 4.4 WebRTC Browser Softphones & Blind Registration
Standard browsers cannot run raw UDP SIP due to browser security restrictions. WebRTC requires:

1. **WSS (Secure WebSockets):** SIP signaling travels over a secure WebSocket (port `7443`) instead of raw UDP. The browser first needs to trust the self-signed certificate by visiting `https://localhost:7443` and clicking "Proceed anyway".
2. **DTLS-SRTP:** All audio must be encrypted.
3. **JsSIP:** The browser-side library that handles SIP over WebSocket.
4. **Blind Registration:** `accept-blind-reg=true` lets the browser register as `sip:akash-freeswitch@localhost` without a directory password entry.

#### How the Browser Registers an Agent
When the agent dashboard loads, [`freeswitch-webrtc.service.ts`](file:///Users/akash.singh/Desktop/MiniGenesys/minigenesys-dashboard/src/app/services/freeswitch-webrtc.service.ts) initializes JsSIP with the agent's ID:

```typescript
const socket = new JsSIP.WebSocketInterface('wss://localhost:7443');
const config = {
  uri: `sip:${agentId}@localhost`,   // e.g. sip:akash@localhost
  password: 'password123',           // FreeSWITCH ignores this — blind reg
  register: true
};
```

JsSIP then:
1. Opens a persistent WebSocket connection to `wss://localhost:7443`.
2. Sends a SIP `REGISTER` message over that WebSocket.
3. FreeSWITCH (with `accept-blind-reg=true`) accepts the registration without checking any password or directory XML entry.
4. FreeSWITCH stores the mapping in its live registration database:
   ```
   sip:akash@localhost  →  this open WebSocket connection
   ```
5. The WebSocket stays open. FreeSWITCH uses it to push inbound SIP `INVITE` messages to the browser when a call arrives.

> [!NOTE]
> The `@` in `sip:akash@localhost` is standard SIP URI syntax (like an email address). It just means user `akash` at domain `localhost`. It is not the same as the `%` used in the backend dial string (see below).

#### The `%localhost` vs `@localhost` Distinction (Critical)
When the Java backend dials an agent via ESL, it uses **`%`** instead of `@`:

```java
// From FreeswitchEslService.java
dialString = "sofia/internal/" + agentId + "%localhost";
// e.g. sofia/internal/akash%localhost
```

| Symbol | Context | Meaning |
| :--- | :--- | :--- |
| `@` | Frontend SIP URI | Standard SIP address format. `sip:akash@localhost` = user `akash` at domain `localhost`. Used for registration. |
| `@` | Backend dial string | Tells FreeSWITCH to look up the user in **`directory/default.xml`** and authenticate. **Fails** if no directory entry exists. |
| `%` | Backend dial string | Tells FreeSWITCH to look up the user in the **live in-memory registration table**. Skips directory lookup entirely. **Required** for blind registrations. |

If the backend used `@localhost` instead of `%localhost`:
```
FreeSWITCH looks in directory/default.xml → finds nothing (empty) → returns 404 Not Found
Browser never rings.
```

#### Registration Forking Risk (Multiple Tabs / Same Agent Name)
Because `accept-blind-reg=true` accepts anyone, if two browser tabs register with the same agent ID:
- FreeSWITCH stores **both** registrations (different WebSocket connections, same SIP URI).
- When the backend originates a call to that agent, FreeSWITCH **forks** the `INVITE` to both tabs simultaneously — both ring.
- The first tab to answer wins; the other receives a `CANCEL`.

> [!WARNING]
> `accept-blind-reg=true` and an empty directory are **development-only** settings. In production, every agent must have a real entry in `directory/default.xml` with a hashed password, and blind registration must be disabled.

---

### 4.5 SDP (Session Description Protocol) Payload Negotiation
While **SIP** handles signaling (dialing, ringing, routing, and hanging up), **SDP** is the negotiation document carried inside the SIP message that describes the actual audio/video media connection.

> [!NOTE]
> Think of SIP as the **courier** and SDP as the **letter inside the envelope** that negotiates the media setup.

#### What Questions Does SDP Answer?
SDP is a plain-text list of key-value parameters that answers these critical questions for both sides of the call:
- **Media Type:** Is this an audio call, video call, or both? (e.g., `m=audio`)
- **IP Address:** What is the IP address where I should send my media packets (RTP)? (the `c=` line)
- **Port:** Which port is open on my side to receive your media? (the `m=` line)
- **Codecs:** Which audio/video compression formats (codecs) do I support? (e.g., Opus, G.711/PCMU)
- **Security:** Are we using standard RTP (`RTP/AVP`) or encrypted SRTP (`RTP/SAVPF`)?

#### What a Real SDP Looks Like (Example)
Below is a standard SDP payload you would find inside a SIP `INVITE` message:

```text
v=0
o=FreeSWITCH 15892 15892 IN IP4 203.0.113.5
s=FreeSWITCH
c=IN IP4 203.0.113.5        <-- Send audio to this IP (Connection Info)
t=0 0
m=audio 16384 RTP/AVP 0 101 <-- Audio media, port 16384, RTP protocol, codecs 0 and 101
a=rtpmap:0 PCMU/8000        <-- Codec 0 is PCMU (standard landline G.711)
a=rtpmap:101 telephone-event/8000 <-- Codec 101 is used for DTMF (keypad tones)
```

- **`c=` line (Connection Info):** Tells the receiver exactly which IP address to send audio/media packets to.
- **`m=` line (Media Info):** Tells the receiver which port to target and which codecs are supported.
- **NAT Issue:** If FreeSWITCH advertises its internal Docker IP (`172.18.0.2`) in the `c=` line, the external carrier/browser cannot route to it, and all audio will fail.

#### The SDP Offer/Answer Negotiation Flow
SDP negotiation operates on an **Offer/Answer** model:
1. **The Offer (e.g., Telnyx → FreeSWITCH):**
   *"I want to start a call. I can send and receive audio using PCMU on IP `148.64.x.x` port `16384`."*
2. **The Answer (e.g., FreeSWITCH → Telnyx):**
   *"Understood. Let's use PCMU. Send your audio to my public IP `203.0.113.5` on port `24510`."*

Once this negotiation is complete, SIP signaling steps aside, and both endpoints begin sending raw audio packets (RTP) directly to the IP addresses and ports negotiated in the SDP.

---

### 4.6 Understanding `sip-ip` vs `ext-sip-ip` vs `rtp-ip` vs `ext-rtp-ip`

| Parameter | What it controls |
| :--- | :--- |
| **`sip-ip`** | The IP the FreeSWITCH SIP socket **listens on** inside the container. Use `0.0.0.0` to listen on all interfaces. |
| **`ext-sip-ip`** | The IP FreeSWITCH **tells the carrier/browser** to use in SIP Contact headers. This must be reachable by whoever is receiving the SIP message. |
| **`rtp-ip`** | The IP FreeSWITCH **binds its RTP audio socket** to inside the container. Use `0.0.0.0` to bind on all interfaces. |
| **`ext-rtp-ip`** | The IP FreeSWITCH **writes in the SDP `c=` line**. This is the IP the browser will send audio to. Must be reachable by the browser. In Docker dev, this must be `192.168.1.4` (your Mac's LAN IP). |

Configuration in `sofia.conf.xml`:
```xml
<param name="sip-ip"     value="0.0.0.0"/>         <!-- Listen on all container interfaces -->
<param name="ext-sip-ip" value="$${FREESWITCH_EXT_IP}"/>  <!-- Tell browser: reach me here -->
<param name="rtp-ip"     value="0.0.0.0"/>         <!-- Bind audio socket on all interfaces -->
<param name="ext-rtp-ip" value="$${FREESWITCH_EXT_IP}"/>  <!-- Tell browser: send audio here -->
```

`FREESWITCH_EXT_IP` is injected from `docker-compose.yml`:
```yaml
environment:
  - FREESWITCH_EXT_IP=${FREESWITCH_EXT_IP:-192.168.1.4}
```

---

### 4.7 Why `0.0.0.0` is Used for Listening but Not for Advertising

`0.0.0.0` is a special OS concept meaning **"listen on every available network interface simultaneously"**. It is a binding instruction to the operating system, not an actual address.

If FreeSWITCH bound to `172.18.0.2` only, connections coming in from the Docker bridge on a different virtual interface would be rejected. Binding to `0.0.0.0` means:
> *"I don't care which interface the packet arrives on. Accept it on all of them."*

However, you **cannot advertise `0.0.0.0`** in SIP headers or SDP because:
- The carrier would receive a SIP `Contact: <sip:...@0.0.0.0>` and try to send packets to `0.0.0.0` — which is not a real address on any network.
- The browser would read `c=IN IP4 0.0.0.0` in the SDP and have no address to send audio to.

So the split is always:
- **Listen on `0.0.0.0`** → accept from everywhere.
- **Advertise `ext-rtp-ip` / `ext-sip-ip`** → tell others the specific, reachable IP to target.

---

# Chapter 5: The Network Layer: NAT, Docker & IP Traversal

### 5.1 Local LAN IP vs. Router Gateway IP vs. Public WAN IP

```text
+-----------------------------------------------------------------------+
|  Public Internet (WAN)                                                |
|  Public WAN IP: 203.0.113.5 (Your router's public face to Telnyx)    |
|                                                                       |
|  +-----------------------------------------------------------------+  |
|  |  Local Wi-Fi Network (LAN)                                      |  |
|  |  Default Gateway IP: 192.168.1.1 (The Router itself)            |  |
|  |  Mac LAN IP: 192.168.1.4 (Your laptop's local address)          |  |
|  |                                                                  |  |
|  |  +-----------------------------------------------------------+  |  |
|  |  |  Docker Virtual Network                                   |  |  |
|  |  |  FreeSWITCH IP: 172.18.0.2 (Private Container address)   |  |  |
|  |  +-----------------------------------------------------------+  |  |
|  +-----------------------------------------------------------------+  |
+-----------------------------------------------------------------------+
```

A packet from Telnyx destined for your FreeSWITCH must cross all three boundaries:
1. **Public Internet → Router:** Telnyx sends to `203.0.113.5:5062`. Your router's WAN IP receives it.
2. **Router → Mac:** Router's port forwarding rule redirects to `192.168.1.4:5062`.
3. **Mac → Container:** Docker's NAT rule forwards from Mac port `5062` → container port `5060` at `172.18.0.2`.

---

### 5.2 How Your System Gets Its IP (DHCP Handshake Step-by-Step)

When your Mac first connects to Wi-Fi with no configuration, it discovers everything through a 4-step handshake:

```text
[ Mac (0e:d0:5d:bf:53:ed) ]                          [ Wi-Fi Router (192.168.1.1) ]
            |                                                        |
            | --- 1. DHCP Discover (Broadcast 255.255.255.255) ----> |
            |     "I have no IP! Who is the router?"                 |
            |                                                        |
            | <-- 2. DHCP Offer (To MAC: 0e:d0:5d:bf:53:ed) ------- |
            |     "I offer you 192.168.1.4."                         |
            |                                                        |
            | --- 3. DHCP Request (Broadcast) --------------------> |
            |     "I accept 192.168.1.4. Lock it in."                |
            |                                                        |
            | <-- 4. DHCP Ack ------------------------------------ |
            |     "Confirmed! IP: 192.168.1.4, Gateway: 192.168.1.1"|
```

After step 4, your Mac has:
- **IP address:** `192.168.1.4`
- **Subnet mask:** `255.255.255.0`
- **Default gateway:** `192.168.1.1` (all internet traffic goes here)
- **DNS server:** `8.8.8.8`

---

### 5.3 Mapping Logical IPs to Physical Hardware (ARP Protocol)
Physical network cards cannot understand IP addresses. They only understand hardware **MAC addresses**. ARP translates between them:

* **Mac shouts (ARP Request):** *"Attention everyone! Who owns `192.168.1.1`? Reply with your MAC address."* (Broadcast to `FF:FF:FF:FF:FF:FF`)
* **Neighbors ignore it:** Phone at `192.168.1.8` reads the request and discards it (not its IP).
* **Router replies (ARP Reply):** *"I am `192.168.1.1`. My MAC address is `aa:bb:cc:dd:ee:ff`."*
* **Mac saves in ARP Cache:**
  ```text
  IP Address    →  Physical (MAC) Address
  192.168.1.1   →  aa:bb:cc:dd:ee:ff
  ```

---

### 5.4 How Docker NAT Works (Apartment Lobby Analogy)
Imagine a massive apartment building (**your Mac**) with one public mailbox address (**`192.168.1.4`**) and 100 internal apartment units (**Docker containers**).

- **NAT (the Lobby Receptionist)** receives all mail at the main entrance.
- When Docker maps `5062:5060`, it says: *"Any mail arriving at Main Door port `5062` should be delivered to Apartment `172.18.0.2`, Room `5060`."*
- The receptionist maintains a logbook of this mapping and forwards packets accordingly.

---

### 5.5 Why Standard Web NAT Works (NAT Logbook / State Table)
Standard web traffic works because **you initiate the connection**. The router creates a logbook entry when you send the first packet:

| Local Device | Local Port | Remote Server | Remote Port | Public Port |
| :--- | :--- | :--- | :--- | :--- |
| `192.168.1.4` | `55432` | `8.8.8.8` | `443` | `12000` |

1. **Your request goes out:** Router replaces source `192.168.1.4:55432` with `203.0.113.5:12000`. Writes logbook entry.
2. **Google replies:** Sends to `203.0.113.5:12000`.
3. **Router checks logbook:** Sees port `12000` belongs to `192.168.1.4:55432`. Translates and delivers.

**Why VoIP breaks this model:** When an inbound call arrives from Telnyx (`203.0.113.5` receives an INVITE), the router has no logbook entry for port `5060` because no outbound connection was initiated. The router drops the packet. This is why **router port forwarding** must be manually configured for VoIP inbound calls.

---

### 5.5b How FreeSWITCH Tells the World Its Real IP: rport + STUN (Verified)

This is one of the most commonly misunderstood topics in SIP/VoIP. There are **two completely separate problems** with two completely separate solutions:

| Problem | Layer | Solution |
| :--- | :--- | :--- |
| How does Telnyx know where to send SIP replies and inbound call INVITEs? | Signaling | **`rport` (RFC 3581)** |
| How does Telnyx know where to send audio/RTP packets? | Media | **STUN + `ext-rtp-ip`** |

---

#### Part 1: SIP Signaling — The `rport` Mechanism (RFC 3581)

When FreeSWITCH boots inside Docker, it is bound to `172.18.0.2`. It has no knowledge of your public WAN IP at the socket/binding level. When it sends a SIP `REGISTER` to Telnyx, it writes its internal address into the packet:

```http
REGISTER sip:sip.telnyx.com SIP/2.0
Via: SIP/2.0/TCP 172.18.0.2:5060;branch=z9hG4bK8y7t6r;rport   ← KEY FLAG
Contact: <sip:usermailakashkrsingh64252@172.18.0.2:5060>       ← Internal Docker IP
```

Notice the `;rport` flag on the `Via` header. This is **FreeSWITCH explicitly instructing Telnyx** via RFC 3581:

> *"I know my Contact header has a private IP that you cannot reach. Do NOT use it to route replies or inbound calls. Instead, look at the actual source IP and source port of the underlying TCP/UDP packet you received. Use that instead."*

When this packet crosses your router, NAT rewrites the outer packet envelope:
```
Inner SIP text:     Via: ... 172.18.0.2:5060;rport   (unchanged)
Outer packet header: Source = 203.0.113.5:5062        (NAT rewrites this)
```

Telnyx receives the packet, sees `;rport`, and follows the RFC 3581 instruction:
1. It reads the outer packet source: `203.0.113.5:5062`.
2. It records this in its registrar database: *"For inbound calls to this account, send SIP INVITE to `203.0.113.5:5062`."*

> [!IMPORTANT]
> Telnyx is **not** being smart here on its own. FreeSWITCH **explicitly tells** Telnyx to use the real packet source via the `;rport` flag. Without this flag, Telnyx would try to send inbound calls to `172.18.0.2:5060` (the Contact header), which is unreachable.

---

#### Part 2: SDP/Media — The STUN + `ext-rtp-ip` Mechanism

The `rport` trick works for SIP signaling because there is already an active TCP connection that Telnyx can observe. But **audio/media is different**:
- Audio travels over **separate UDP streams** on dynamically allocated ports (e.g., 16000–32000).
- These streams are brand new — they have not been established yet when FreeSWITCH must write the SDP offer.
- Telnyx has no existing connection to look at. It must blindly send UDP audio to whatever IP and port FreeSWITCH writes in the SDP `c=` line.

This is where **STUN** is used. In your [sofia.conf.xml](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/sofia.conf.xml), the external profile has:
```xml
<param name="ext-rtp-ip" value="stun:stun.l.google.com:19302"/>
```

At startup, `mod_sofia` performs a one-shot STUN query:
1. FreeSWITCH sends a STUN binding request to `stun.l.google.com:19302`.
2. Google's STUN server sees the packet arrive from `203.0.113.5:someport` (your public WAN IP — the NAT-translated address).
3. STUN replies: *"Your public address is `203.0.113.5`."*
4. FreeSWITCH caches this public IP internally.
5. When generating SDP for any call, it writes the STUN-discovered IP instead of its Docker IP:
   ```text
   c=IN IP4 203.0.113.5   ← Telnyx can send audio here ✓
   ```

---

#### Complete Verified Flow: SIP REGISTER → Inbound Call

```text
[Boot] FreeSWITCH queries STUN → discovers public IP: 203.0.113.5
       FreeSWITCH sends REGISTER to sip.telnyx.com:
         Via: 172.18.0.2:5060;rport        ← "Use real packet source for replies"
         Contact: 172.18.0.2:5060          ← internal (will be overridden by rport)

[NAT]  Router rewrites envelope:
         Outer packet source: 203.0.113.5:5062

[Telnyx] Sees ;rport → ignores Contact → records 203.0.113.5:5062 for signaling

[Customer calls your number]
  Telnyx sends SIP INVITE to: 203.0.113.5:5062  (signaling, via rport record)
  Router forwards → Mac → Docker → FreeSWITCH

  FreeSWITCH answers with SIP 200 OK + SDP:
    c=IN IP4 203.0.113.5   (STUN-discovered IP, written into SDP)
    m=audio 24816 RTP/AVP ...

  Telnyx reads SDP → sends audio UDP packets to: 203.0.113.5:24816
  Router port-forwards → Mac → Docker → FreeSWITCH receives audio ✓
```

---

### 5.5c The Bidirectional NAT Rewrite & Docker Port Mapping

NAT rewrite happens in **both directions**, but it changes a different part of the IP envelope depending on which way the packet is going.

Here is exactly what happens on your router/Docker network:

#### 1. Outbound (FreeSWITCH sends to Telnyx)
When the packet leaves FreeSWITCH, the router performs **Source NAT (SNAT)**. It rewrites the *return address* so Telnyx knows where to send replies.

* **Before NAT (leaving container):**
  * Source IP: `172.18.0.2` (Docker IP)
  * Destination IP: `148.64.x.x` (Telnyx IP)
* **After NAT (leaving your router):**
  * Source IP: `203.0.113.5` (Your Public WAN IP) 👈 *Rewritten!*
  * Destination IP: `148.64.x.x` (Telnyx IP)

#### 2. Inbound (Telnyx sends to FreeSWITCH)
When Telnyx replies, the packet hits your router. The router performs **Destination NAT (DNAT)**, also known as Port Forwarding. It rewrites the *target address* so it can deliver it to your private network.

* **Before NAT (arriving at your router):**
  * Source IP: `148.64.x.x` (Telnyx IP)
  * Destination IP: `203.0.113.5` (Your Public WAN IP)
* **After NAT (arriving at your container):**
  * Source IP: `148.64.x.x` (Telnyx IP)
  * Destination IP: `172.18.0.2` (Docker IP) 👈 *Rewritten!*

#### The Dual-NAT of Docker Port Mapping (`5062:5060`)
Because you run FreeSWITCH in Docker, you actually have **two layers of NAT** happening simultaneously:
1. **Your Airtel Router's NAT** (changing the IP between `192.168.1.4` and your public `203.0.113.5`).
2. **Docker's NAT** (changing the port between `5062` and `5060`, and the IP between `172.18.0.2` and `192.168.1.4`).

When you write `"5062:5060"` in `docker-compose.yml`, you are literally creating a NAT rule inside your Mac:
* **Inbound:** Docker NAT intercepts the envelope, erases `Destination Port: 5062`, writes `5060`, and slides it into the FreeSWITCH container.
* **Outbound:** Docker NAT intercepts the envelope leaving the container, erases `Source Port: 5060`, and writes `5062` before sending it to the Airtel router.

### 5.5d Example: Raw SIP REGISTER Payload

Here is a real example of the exact plain-text payload FreeSWITCH generates and puts inside the TCP/UDP envelope when it registers with Telnyx. FreeSWITCH generates this text *before* it passes it to Docker or the router, so it only knows its internal Docker IP (`172.18.0.2`).

```http
REGISTER sip:sip.telnyx.com SIP/2.0
Via: SIP/2.0/UDP 172.18.0.2:5060;rport;branch=z9hG4bK-abc123def
From: <sip:your_telnyx_username@sip.telnyx.com>;tag=987654321
To: <sip:your_telnyx_username@sip.telnyx.com>
Call-ID: 1a2b3c4d5e6f7g8h9i0j@172.18.0.2
CSeq: 1 REGISTER
Contact: <sip:your_telnyx_username@172.18.0.2:5060>
Expires: 3600
Content-Length: 0
```

#### 1. The `Contact` header (The unreachable return address)
```http
Contact: <sip:your_telnyx_username@172.18.0.2:5060>
```
FreeSWITCH is saying: *"If someone calls my Telnyx number, please forward the call to `172.18.0.2`."* Because this is a private IP, Telnyx would fail if it blindly obeyed this.

#### 2. The `Via` header (The `rport` savior)
```http
Via: SIP/2.0/UDP 172.18.0.2:5060;rport;branch=z9hG4bK-abc123def
```
By adding `;rport` (RFC 3581), FreeSWITCH is telling Telnyx:
> *"Hey Telnyx! I am behind a NAT router. I know I wrote `172.18.0.2` in the Contact header, but **please ignore it**. Instead, look at the physical IP envelope that this letter arrived in, and send all future calls to whatever IP and Port you see on the outside of the envelope!"*

When Telnyx sees `;rport`, it ignores the `Contact` text, reads the public `203.0.113.5:5062` from the router's NAT envelope, and saves *that* in its database.

---

### 5.6 Why NAT Breaks VoIP/WebRTC (The SDP Payload Problem)

**The crucial insight:** SIP and WebRTC write IP addresses **inside the text payload of the message (SDP)**, not just in the outer network header.

Docker's NAT translates the outer packet header, but **does not modify the SDP text content inside**.

```
BEFORE Docker NAT:
  Outer IP Header:  Source = 172.18.0.2  (Docker IP)
  SDP Payload text: c=IN IP4 172.18.0.2  (Docker IP written inside text)

AFTER Docker NAT (outer header translated, SDP unchanged):
  Outer IP Header:  Source = 192.168.1.4 (Mac IP - TRANSLATED ✓)
  SDP Payload text: c=IN IP4 172.18.0.2  (STILL the Docker IP! NOT translated ✗)
```

**What the browser does with this:**
> Browser reads the SDP text: *"Send audio to `172.18.0.2`."*
> Browser asks Mac OS to route packets to `172.18.0.2`.
> Mac OS routing table: *"I have no route to `172.18.0.2`. Dropping packets."*
> Result: **Complete silence. Call drops after 8-10 seconds (ICE timeout).**

**The Fix:** Configure `ext-rtp-ip=192.168.1.4` in FreeSWITCH so it writes the correct reachable host IP in the SDP:
```text
c=IN IP4 192.168.1.4   ← Browser can route to this!
```

---

### 5.6b The Carrier Loophole: Symmetric Routing & Media Latching

By strict textbook rules, if FreeSWITCH sends a private Docker IP (`172.18.0.2`) to a public carrier like Telnyx, the call **must fail**:
1. **Signaling Failure:** Telnyx cannot route SIP replies (`BYE`, `ACK`) to the private IP written in the `Contact` header.
2. **Media Failure:** Telnyx cannot route audio to the private IP written in the SDP `c=` line.

However, we proved in testing that the call **still works perfectly** even when `ext-sip-ip` and `ext-rtp-ip` are omitted. Why?

Because modern carriers (like Telnyx and Twilio) employ two massive safety nets on their edge SBCs (Session Border Controllers):

**1. Bypassing broken `ext-sip-ip` (Symmetric Routing via `rport`)**
Instead of trusting the IP address written inside the `Contact` header, Telnyx looks at the `Via` header. Because FreeSWITCH includes the `;rport` flag (RFC 3581), Telnyx is instructed to ignore the `Contact` text entirely. Instead, it reads the physical network envelope (the NAT-translated source IP and Port) that the packet arrived from, and sends all future SIP signaling back to that physical address.

**2. Bypassing broken `ext-rtp-ip` (Symmetric RTP / Media Latching)**
Instead of trusting the IP address written inside the SDP (`c=IN IP4 172.18.0.2`), Telnyx intentionally ignores the SDP text. It sits in silence and waits. Because the FreeSWITCH conference module immediately starts playing ringback/background noise, FreeSWITCH sends the first UDP audio packet out to the internet. When Telnyx receives that first audio packet, it looks at the physical network envelope and "latches" onto it, sending all return audio back to that physical NAT address.

**Why `ext-sip-ip` and `ext-rtp-ip` are still best practice:** 
Symmetric RTP only works if your server sends audio *first*. If FreeSWITCH answered the call silently (e.g., muted agent), Telnyx would never receive a packet to latch onto, resulting in one-way audio. Additionally, strict legacy PBXs (like Avaya or Cisco) do not use Symmetric RTP or `rport`—they will drop the call instantly if the IPs in the text payload are wrong.

**If Telnyx ignores the IP, why send the SDP at all?**
Even though the media server (RTP) ignores the IP address, the signaling server (SIP) strictly requires the SDP for two reasons:
1. **Codec Negotiation:** The SDP contains the list of supported audio languages (e.g., PCMU, Opus). If omitted, Telnyx wouldn't know how to encode the audio, leading to robotic noise or failure.
2. **Protocol Compliance:** RFC 3261 rigidly requires an SDP Answer to an SDP Offer. If FreeSWITCH replied with a `200 OK` lacking an SDP body, Telnyx's SIP stack would immediately drop the call with an error before the audio even had a chance to start.

---

### 5.7 ICE, STUN & TURN: Traversing NAT for WebRTC Media

WebRTC (the internal profile connecting FreeSWITCH to your Angular browser) uses a totally different NAT traversal mechanism than standard SIP. Instead of relying on carriers to fix things with Symmetric RTP, WebRTC uses a strict protocol called **ICE (Interactive Connectivity Establishment)** to find the best path for media to travel.

#### Why `ext-rtp-ip` is Life-or-Death for WebRTC
WebRTC is completely unforgiving. Before Google Chrome sends a single drop of audio, it demands a valid "ICE Candidate" (an IP and a Port) from FreeSWITCH. Chrome will physically test that IP by sending a STUN Binding Request (a ping). If the ping fails, Chrome immediately gives up and drops the call.

If you omitted `ext-rtp-ip` from the internal profile, FreeSWITCH would put its internal Docker IP (`172.18.0.2`) into the SDP. Chrome would try to ping `172.18.0.2`, your Mac OS would drop the packet (since it can't route to the Docker bridge directly), the ICE check would fail, and the call would drop after 8 seconds of silence. This is why we set `ext-rtp-ip` to `$${external_rtp_ip}` (`192.168.1.4`)—so Chrome pings the reachable LAN IP of your Mac.

#### Why `ext-sip-ip` is Cosmetic for WebRTC (WebSockets)
While `ext-rtp-ip` controls the UDP Media, `ext-sip-ip` controls the SIP Signaling (`Contact` header). However, for WebRTC, this IP doesn't actually matter!

WebRTC signaling doesn't use standard UDP packets; it uses **WebSockets (`wss://`)**. When your Angular dashboard connects to FreeSWITCH, it opens a persistent, bidirectional WebSocket tunnel. Because that tunnel is permanently held open, FreeSWITCH doesn't need to look up an IP address to send a SIP message back to the browser. It simply pushes the text frame straight down the existing WebSocket pipe. Even if the IP in the `Contact` header was completely broken, the browser's operating system never uses it to route network traffic. We still configure it to `$${external_rtp_ip}` to keep the logs clean and prevent strict parser errors, but it has no impact on routing.

#### The WebRTC Offer/Answer Timeline (When is SDP sent?)
WebRTC and SIP use a handshake called the **Offer/Answer Model**. The SDP is generated and sent to Angular *before* the agent ever clicks "Accept". Here is the exact timeline:

1. **The `INVITE` (The Offer):** When the Java backend commands FreeSWITCH to dial the agent, FreeSWITCH constructs a SIP `INVITE` message. It looks at `ext-rtp-ip`, writes that IP (`192.168.1.4`) into an **SDP Offer**, and pushes the `INVITE` down the WebSocket to Angular.
2. **The Popup Appears:** Your Angular app (via `JsSIP`) receives the `INVITE` and the SDP Offer. It saves FreeSWITCH's IP into memory and triggers the UI popup to ring.
3. **The `200 OK` (The Answer):** When you click "Accept", `JsSIP` asks Chrome for microphone access, generates its own **SDP Answer** (containing your Mac's local IP), and sends it back up the WebSocket to FreeSWITCH inside a `200 OK`.
4. **The ICE Ping:** Immediately after exchanging SDPs, Chrome fires a UDP "ping" (STUN Binding Request) to the IP it saved from Step 1 (`192.168.1.4`). If FreeSWITCH receives the ping and replies, the audio channel opens.

**Candidate Types:**
1. **Host Candidates:** The direct local IP of each device (e.g., `192.168.1.4:51004` for the browser).
2. **Server Reflexive Candidates (STUN):** The public IP/port discovered by asking a STUN server: *"What do you see my packets coming from?"*
3. **Relay Candidates (TURN):** A public relay server that forwards media when direct connection fails.

**The ICE Negotiation (Browser ↔ FreeSWITCH):**
1. **Gather:** Both sides collect all their candidate addresses.
2. **Exchange:** Candidates swapped inside SDP messages.
3. **Ping Race:** Both sides test every candidate pair simultaneously.
   - Browser → FreeSWITCH's Docker IP `172.18.0.2` → **Silence** (unreachable from host OS)
   - Browser → FreeSWITCH's ext-rtp-ip `192.168.1.4` → **Reply!** (routable on LAN)
4. **Select:** The working path wins. DTLS encryption handshake starts. Audio flows.

**Two Additional Config Fixes Required in Docker:**

* **`ndlb-force-ctx-ip=true`:** Docker masquerades WSS connections, making them appear to come from the bridge gateway `172.18.0.1`. FreeSWITCH detects this and falls back to using `172.18.0.2` in SDP. This flag disables that fallback and forces use of `ext-rtp-ip`.
* **`apply-candidate-acl=lan`:** When `ext-rtp-ip` is set, FreeSWITCH's ICE candidate filter drops RFC1918 private IP candidates by default (treating itself as external). Since the browser IS on the local LAN (`192.168.1.4`), we must tell FreeSWITCH to accept private candidates using the `lan` ACL.

---

# Chapter 6: End-to-End Inbound Call Flow

### 6.1 Complete Packet-Level Call Lifecycle Diagram

```text
[ Customer Phone ]     [ FreeSWITCH (Docker) ]     [ Java Backend ]     [ Agent Browser (JsSIP) ]
       |                          |                         |                         |
       | -1. SIP INVITE (SDP)---> |                         |                         |
       |    (via Telnyx, UDP)     |                         |                         |
       |                          | -2. ESL CHANNEL_PARK -> |                         |
       |                          |    (TCP port 8022)      |                         |
       |                          |                         | -3. WS JSON ----------> |
       |                          |                         |  INCOMING_CALL event    |
       |                          |                         |                         | (Agent clicks Accept)
       |                          | <-4. SIP INVITE (SDP)-- |                         |
       |                          |    (via WSS port 7443)  |                         |
       |                          | -5. SIP 200 OK (SDP) -> |                         |
       |                          |    (via WSS port 7443)  |                         |
       |                          |                         | -6. ESL uuid_bridge --> |
       |                          |                         |    (TCP port 8022)      |
       | <======= 7. Mixed RTP Audio (UDP 16384-16400) ==========================>   |
```

---

### 6.2 Step 1: Inbound Call Arrives from Carrier (Telnyx → FreeSWITCH)

A customer dials your support number. Telnyx (configured with your public IP) sends a SIP `INVITE` to `203.0.113.5:5062`.

**The packet journey:**
```
Telnyx Server (148.64.x.x:5060)
    | UDP Packet: INVITE sip:+91XXXXXXXXXX@203.0.113.5:5062
    ↓
Your Router (203.0.113.5)
    | Port Forwarding Rule: 5062 → 192.168.1.4:5062
    ↓
Your Mac (192.168.1.4:5062)
    | Docker Port Mapping: 5062 → 172.18.0.2:5060
    ↓
FreeSWITCH External Profile (172.18.0.2:5060)
    | Routes to "public" dialplan context
    ↓
public.xml → answer + park
```

The SIP INVITE contains the Telnyx SDP Offer:
```text
c=IN IP4 148.64.x.x      (Telnyx IP — FreeSWITCH will send audio here)
m=audio 16384 RTP/AVP 0  (Telnyx port — G.711 codec)
```

FreeSWITCH answers and parks. The customer hears hold music (`moh-sound`).

---

### 6.3 Step 2: ESL Event Arrives at Java (FreeSWITCH → freeswitch-service)

The moment `park` executes, FreeSWITCH fires a `CHANNEL_PARK` event over the persistent ESL TCP connection:

```text
Event-Name: CHANNEL_PARK
Unique-ID: abc-123-uuid                    ← FreeSWITCH channel UUID (primary key for this call)
Call-Direction: inbound
Caller-Caller-ID-Number: +919876543210     ← Customer's number
Caller-Destination-Number: +911234567890  ← Your DID number
Channel-Call-State: ACTIVE
```

The `Unique-ID` is the FreeSWITCH **channel UUID** — used as the primary key for this call leg in all subsequent steps.

---

### 6.4 Step 3: Session Created & call-service Notified

`handleChannelPark()` in `FreeswitchEslService.java` runs:

1. **Idempotency check:** If `repository.existsById(uuid)` → return (don't double-process).
2. **Direction check:** Only process `inbound` direction parks.
3. **Create call record:** POST to `call-service` → returns `internalCallId`.
4. **Save local session:**
   ```java
   FreeswitchCallSession { customerUuid="abc-123", internalCallId="call-789", status="PARKED" }
   ```
5. `call-service` publishes a routing request to Kafka.

---

### 6.5 Step 4 & 5: Routing via Kafka (call-service → routing-service → Kafka)

Asynchronously, outside `freeswitch-service`:
- `call-service` → Kafka topic `routing-requests`
- `routing-service` finds an available agent in Redis
- `routing-service` → Kafka topic `routing-events`:
  ```json
  {
    "callId": "call-789",
    "agentId": "akash-freeswitch",
    "status": "ASSIGNED",
    "telephonyProvider": "FREESWITCH"
  }
  ```

---

### 6.6 Step 6: freeswitch-service Receives Routing Event

`RoutingEventConsumer` → `FreeswitchCallService.handleAssignment()`:

```java
// 1. Skip if not for FreeSWITCH
if (!"FREESWITCH".equals(event.getTelephonyProvider())) return;

// 2. Find the session
FreeswitchCallSession session = repository.findByInternalCallId("call-789");

// 3. Pre-generate agent channel UUID
String agentUuid = UUID.randomUUID().toString(); // "def-456-uuid"

// 4. Update session
session.setAgentUuid("def-456-uuid");
session.setStatus("DIALING_AGENT");
```

---

### 6.7 Step 7: Customer Transferred to Conference + Agent Dialed

Two async ESL commands fire almost simultaneously:

**Command 1: Move customer into a conference room:**
```text
ESL: uuid_transfer abc-123-uuid conference:abc-123-uuid@default inline
```
FreeSWITCH creates conference room `abc-123-uuid@default` and places the customer in it.

**Command 2: Dial the agent into the same room:**
```text
ESL: originate {origination_uuid=def-456-uuid,media_webrtc=true,rtp_secure_media=true}
       sofia/internal/akash-freeswitch%localhost
       &conference(abc-123-uuid@default)
```

> **Why `%localhost` instead of `@localhost`?**
> - `@localhost` = look up agent in `directory/default.xml` XML file → **fails** (directory is empty in dev).
> - `%localhost` = look up agent in FreeSWITCH's **live in-memory registration table** → **succeeds** because the browser registered via WebSocket at startup.
> See Section 4.4 for the complete breakdown of registration flow and credential handling.

FreeSWITCH sends a SIP INVITE over WSS (`wss://192.168.1.4:7443`) to the registered browser. The browser's JsSIP shows the incoming call popup.

---

### 6.8 Step 8: Agent Answers (CHANNEL_ANSWER)

Agent clicks "Accept". The browser's SDP Offer is sent over WSS:
```text
c=IN IP4 192.168.1.4   (Browser's IP)
m=audio 51004 RTP/SAVPF 111 9  (Browser's audio port)
```

FreeSWITCH responds with `200 OK` and its SDP Answer. Because `ext-rtp-ip` is set correctly:
```text
c=IN IP4 192.168.1.4   (Host Mac IP — NOT the Docker container IP!)
m=audio 24816 RTP/SAVPF 111  (FreeSWITCH's audio port)
```

ICE candidates are exchanged. The `192.168.1.4` candidate wins. DTLS handshake completes. Audio starts.

`handleChannelAnswer()` in Java detects the outbound agent leg answers:
```java
session.setStatus("BRIDGED");
callServiceClient.startCall(tenantId, internalCallId);  // Call is IN_PROGRESS
```

---

### 6.9 Step 9: Hangup & Cleanup (CHANNEL_HANGUP_COMPLETE)

Either party hangs up. FreeSWITCH fires `CHANNEL_HANGUP_COMPLETE` for the hung-up channel UUID.

`handleChannelHangupComplete()`:
```java
// Determine who hung up (customer or agent)
// Kill the OTHER leg
client.sendAsyncApiCommand("uuid_kill", otherPartyUuid);

// Update session
session.setStatus("COMPLETED");
callServiceClient.completeCall(tenantId, internalCallId);
```

The conference room empties and FreeSWITCH destroys it automatically.

---

# Chapter 7: FreeSWITCH Configuration Reference

### 7.1 `sofia.conf.xml` Key Parameters Explained

| Parameter | Value | What it does |
| :--- | :--- | :--- |
| `sip-ip` | `0.0.0.0` | Bind SIP socket to all container interfaces |
| `ext-sip-ip` | `$${FREESWITCH_EXT_IP}` | Advertise this IP in SIP Contact headers |
| `rtp-ip` | `0.0.0.0` | Bind RTP audio socket to all container interfaces |
| `ext-rtp-ip` | `$${FREESWITCH_EXT_IP}` | Write this IP in SDP `c=` line for media |
| `sip-port` | `5060` | Internal SIP signaling port |
| `context` | `public` / `default` | Which dialplan to route calls to |
| `accept-blind-reg` | `true` | Accept browser registrations without directory entry |
| `auth-calls` | `false` | Don't require password for browser SIP calls |
| `ndlb-force-ctx-ip` | `true` | Force `ext-rtp-ip` even when Docker masks connections |
| `apply-candidate-acl` | `lan` | Accept private-IP ICE candidates from LAN browsers |

---

### 7.2 `event_socket.conf.xml` & ACL Security

```xml
<configuration name="event_socket.conf">
  <settings>
    <param name="nat-map"         value="false"/>
    <param name="listen-ip"       value="0.0.0.0"/>  <!-- Listen on all container interfaces -->
    <param name="listen-port"     value="8021"/>       <!-- Internal port (host maps 8022 → 8021) -->
    <param name="password"        value="ClueCon"/>    <!-- Java auth password -->
    <param name="apply-inbound-acl" value="lan"/>      <!-- Named ACL (not raw CIDR) -->
  </settings>
</configuration>
```

---

### 7.3 `switch.conf.xml` RTP Port Range

```xml
<param name="rtp-start-port" value="16384"/>
<param name="rtp-end-port"   value="16400"/>
```

These ports must exactly match the Docker Compose UDP port range mapping:
```yaml
- "16384-16400:16384-16400/udp"
```

---

### 7.4 `vars.xml` Global Variables

```xml
<X-PRE-PROCESS cmd="set" data="external_rtp_ip=192.168.1.4"/>
<X-PRE-PROCESS cmd="set" data="external_sip_ip=192.168.1.4"/>
```

These values are injected via the `FREESWITCH_EXT_IP` environment variable in Docker Compose.

---

# Chapter 8: ACL & Network Security

### 8.1 What ACLs Do
ACLs (Access Control Lists) act as IP-level firewall rules inside FreeSWITCH. They control which source IP addresses are permitted to connect to the ESL socket or register as SIP clients.

### 8.2 `acl.conf.xml` Configuration

```xml
<list name="lan" default="deny">
  <node type="allow" cidr="127.0.0.1/32"/>        <!-- Localhost -->
  <node type="allow" cidr="10.0.0.0/8"/>           <!-- Private class A -->
  <node type="allow" cidr="172.16.0.0/12"/>        <!-- Private class B (Docker bridge) -->
  <node type="allow" cidr="192.168.0.0/16"/>       <!-- Private class C (LAN) -->
</list>
```

`default="deny"` means: reject any IP not explicitly listed. The three CIDR ranges cover:
- Your Mac's LAN IP (`192.168.1.4`)
- Docker's bridge IP (`172.18.0.1`)
- Any container-to-container traffic

### 8.3 `event_socket.conf.xml` Security Hardening
- `apply-inbound-acl` must reference a **named ACL** (`"lan"`), not a raw CIDR string (`"0.0.0.0/0"`). Raw CIDR notation is syntactically invalid for this parameter and will leave the ESL socket completely open.

---

# Chapter 9: Debugging Reference

### 9.1 Docker & Container Inspection

```bash
# Verify container is running and port mappings are active
docker ps -a --filter name=minigenesys-freeswitch-mvp

# View recent FreeSWITCH logs
docker logs minigenesys-freeswitch-mvp --since 5m 2>&1

# Stream logs live
docker logs -f minigenesys-freeswitch-mvp
```

---

### 9.2 FreeSWITCH Engine Status

```bash
# Core status (uptime, active sessions, max sessions)
docker exec minigenesys-freeswitch-mvp fs_cli -x "status"

# Reload all XML configs without restarting
docker exec minigenesys-freeswitch-mvp fs_cli -x "reloadxml"

# Check if a specific module is loaded
docker exec minigenesys-freeswitch-mvp fs_cli -x "module_exists mod_conference"
```

---

### 9.3 ESL Connectivity

```bash
# Raw TCP check from host — verifies Docker port mapping works
nc -zv localhost 8022

# Interactive ESL — watch all events stream in real-time
docker exec -it minigenesys-freeswitch-mvp fs_cli
  /event plain ALL
```

---

### 9.4 Sofia SIP Status & Gateway Verification

```bash
# List all SIP profiles and their status
docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia status"

# Show currently registered WebRTC/SIP clients (browsers)
docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia status profile internal reg"

# Restart a single profile without restarting the container
docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia profile internal restart"

# Enable SIP message tracing (shows full INVITE/SDP/200 OK payloads)
docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia profile internal siptrace on"
docker exec minigenesys-freeswitch-mvp fs_cli -x "console loglevel debug"
```

For Telnyx gateway registration: look for `REGED` status. If you see `NOREG` or `FAIL_WAIT`, credentials or network routing are wrong. Use `docker restart` (not `reloadxml`) to re-send a fresh `REGISTER` to the carrier.

---

### 9.5 WebRTC / SIP Registration Debugging

```bash
# Check registered browser clients
docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia status profile internal reg"
```

If registration fails due to self-signed TLS certificate rejection in the browser:
1. Navigate to `https://localhost:7443` in Chrome/Firefox.
2. Click **"Advanced"** → **"Proceed to localhost (unsafe)"**.
3. Reload the dashboard. JsSIP will now accept the certificate.

---

### 9.6 RTP & Media Flow Debugging

```bash
# List all active audio channels with IPs, codecs, and ports
docker exec minigenesys-freeswitch-mvp fs_cli -x "show channels"
```

If you see channels but hear no audio, check that:
1. `ext-rtp-ip` matches your actual Mac LAN IP (not `172.18.x.x`).
2. Docker UDP port range `16384-16400` is published in `docker-compose.yml`.
3. No OS-level firewall is blocking incoming UDP on those ports.

---

### 9.7 Conference Module Debugging

```bash
# Verify mod_conference is loaded
docker exec minigenesys-freeswitch-mvp fs_cli -x "module_exists mod_conference"

# List all active conference rooms and their members
docker exec minigenesys-freeswitch-mvp fs_cli -x "conference list"
```

Example output showing a bridged customer + agent:
```
Conference abc-123-uuid (2 members rate: 8000 flags: running)
  4;sofia/internal/akash-freeswitch;def-456-uuid;Agent Browser;hear|speak|floor
  3;sofia/external/customer-leg;abc-123-uuid;+919876543210;hear|speak
```

---

### 9.8 Call Control & Simulation Commands

```bash
# Simulate an inbound call without a real carrier
docker exec minigenesys-freeswitch-mvp fs_cli -x "originate loopback/1234/public &park"

# Kill a specific call leg by UUID
docker exec minigenesys-freeswitch-mvp fs_cli -x "uuid_kill <uuid>"

# Reset stuck agent state in Postgres
docker exec <postgres_container> psql -U postgres -d minigenesys_call_service \
  -c "UPDATE agents SET status='AVAILABLE', active_call_id=NULL WHERE agent_id='akash-freeswitch';"

# Clear stuck calls from Redis
docker exec <redis_container> redis-cli keys "tenant:tenant-freeswitch:call:*"
docker exec <redis_container> redis-cli del <key>
```

---

# Chapter 10: Known Bugs, Root Causes & Fixes

### 10.1 Calls Not Ringing the Agent Browser

**Symptom:** Inbound PSTN call connects to FreeSWITCH, parks, ESL event received, but no browser popup appears.

**Root cause:** `routing-service` was publishing `RoutingEvent` with `telephonyProvider: null`. `freeswitch-service` only processes events where `telephonyProvider == "FREESWITCH"`, so it silently skipped every event.

**Fix:** Updated `RoutingEngine` and `AssignmentResult` to copy `telephonyProvider` from the `Call` entity into the Kafka `RoutingEvent` message.

---

### 10.2 Wrong Dial String for WebRTC Registrations (`%localhost`)

**Symptom:** `freeswitch-service` sends `originate` command, FreeSWITCH logs `USER_NOT_REGISTERED` or `NO_ROUTE_DESTINATION`. Browser never rings.

**Root cause:** We used `sofia/internal/sip:agentId@localhost`. This tells FreeSWITCH to look up `agentId` in the directory XML. Since the directory is empty (blind registration), it fails.

**Fix:** Use `sofia/internal/agentId%localhost`. The `%` syntax tells FreeSWITCH to look up the agent's active in-memory WebSocket registration, bypassing directory XML validation.

---

### 10.3 ICE Failure — FreeSWITCH Advertising Docker IP in SDP

**Symptom:** Browser popup appears. Agent clicks Accept. Call connects briefly then drops exactly 8 seconds later. No audio at any point.

**Root cause:** FreeSWITCH was advertising its Docker container IP (`172.18.0.2`) in the SDP `c=` line. The browser tried to send audio to `172.18.0.2`, which the host OS cannot route. ICE timed out (8 second browser timeout) and the browser sent `BYE`.

**Evidence found:**
```text
SIP Trace showed: c=IN IP4 172.18.0.2 in FreeSWITCH SDP Answer
Browser log: ICE timeout → BYE sent after 8000ms
```

**Fix:**
1. Set `ext-rtp-ip` and `ext-sip-ip` to `$${FREESWITCH_EXT_IP}` in `sofia.conf.xml`.
2. Set `ndlb-force-ctx-ip=true` to override Docker's connection masquerading.
3. Set `apply-candidate-acl=lan` to allow private IP ICE candidates from the browser.
4. Inject `FREESWITCH_EXT_IP=192.168.1.4` via Docker environment variable.

---

### 10.4 Frontend Status Desync on Call Rejection

**Symptom:** Agent rejects a call. Frontend logs out agent to `Offline` (correct). Backend sends `CALL_COMPLETED` event. Frontend sees this event and transitions agent back to `Ready` (wrong). Agent can't receive further calls.

**Root cause:** The `CALL_COMPLETED` WebSocket handler in `session-state.service.ts` unconditionally set status to `Ready`, without checking the agent's current status.

**Fix:** Added guard: only transition to `Ready` on `CALL_COMPLETED` if current status is NOT `Offline`.

---

### 10.5 Dialplan Blocking on `tone_stream` Loop

**Symptom:** Inbound call is answered but never parks. `CHANNEL_PARK` ESL event never fires. Call appears stuck.

**Root cause:** The dialplan had:
```xml
<action application="playback" data="tone_stream://%(1000,0,800)"/>
```
Without a `loops=1` parameter, `tone_stream` plays indefinitely. The dialplan execution blocks on this action forever and never reaches `park`.

**Fix:** Removed the `playback` action. Ringback is now handled inside the conference room via `moh-sound=tone_stream://%(2000,4000,440,480)` in `conference.conf.xml`.

---

### 10.6 Common Failure Cases & Diagnosis Guide

| Symptom | Most Likely Cause | How to Diagnose |
| :--- | :--- | :--- |
| Telnyx INVITE never arrives | Router port forwarding not configured | Check router admin panel for port `5062` → `192.168.1.4` rule |
| INVITE arrives, 403 Forbidden | ACL blocking Telnyx IP | Check `acl.conf.xml` — ensure Telnyx IP is allowed |
| Call connects but no audio | `ext-rtp-ip` wrong or RTP ports not mapped | Check `sofia status` for ext-rtp-ip. Check `docker-compose.yml` for UDP port range |
| One-way audio | SDP asymmetry — one side has wrong IP | Enable `siptrace on` and examine both SDP offers/answers |
| Call drops after 8-10 seconds | ICE failure / `172.18.x.x` in SDP | Enable `console loglevel debug`. Look for ICE timeout logs |
| Browser never shows popup | originate command failing | Check `CHANNEL_ANSWER` in FreeSWITCH logs. Verify `%localhost` dial string |
| Agent stuck `Offline` after reject | Frontend status desync | Check `session-state.service.ts` CALL_COMPLETED handler |
| ESL connection refused | Docker port `8022` not mapped or ACL blocking | `nc -zv localhost 8022`. Check `acl.conf.xml` |

---

### 10.7 WebSocket Connection Fails After Session Expiry (Infinite Retry Loop)

**Symptom:** After an agent session expires (or they log out and log back in), the dashboard fails to receive WebSocket events. The backend gateway logs repeatedly show `Invalid or expired token`.

**Root cause:** The frontend Angular `WebsocketService` initialized the `stompjs` client with a static `connectHeaders` object on page load. When the STOMP client automatically tried to reconnect after being disconnected, it reused this stale, cached token from the initial load, rather than fetching the fresh token that was put into `localStorage` after the re-login.

**Fix:** Added a `beforeConnect` hook to the STOMP client configuration to dynamically fetch the latest JWT from `localStorage` right before every single reconnection attempt.

---

# Chapter 11: MVP Integration Changelog

### 11.1 Phase 0: Architecture Decision
**Decision:** FreeSWITCH logic lives in its own dedicated microservice `freeswitch-service`, separate from `telephony-service` (Twilio).

| Decision | Detail |
| :--- | :--- |
| Why separate service? | Avoids dependency clashes (Netty ESL vs. Twilio SDK); independent lifecycle |
| Who orchestrates calls? | `call-service` — stores `telephonyProvider` on every `Call` entity |
| Mid-call controls | Frontend → `call-service` REST → routes to `telephony-service` or `freeswitch-service` |
| Twilio flow | 100% untouched |
| Session state | `freeswitch_call_sessions` table maps FreeSWITCH UUIDs to internal call IDs |

---

### 11.2 Phase 1: FreeSWITCH Docker & Configuration
Built a minimal, production-ready FreeSWITCH container. Key files:

| File | Purpose |
| :--- | :--- |
| `docker-compose.yml` | Container with SIP, WSS, ESL, RTP port mappings |
| `freeswitch.xml` | Root XML config loader |
| `modules.conf.xml` | Reduced module set |
| `event_socket.conf.xml` | ESL socket binding |
| `switch.conf.xml` | RTP port range 16384–16400 |
| `sofia.conf.xml` | SIP profiles with WebRTC support |
| `dialplan/public.xml` | Inbound: answer + park |

---

### 11.3 Phase 2: `freeswitch-service` Spring Boot Bootstrap
New Spring Boot microservice on port **8093** with health endpoints and Postgres database `minigenesys_freeswitch`.

---

### 11.4 Phase 3: ESL Connection & Event Logging
`FreeswitchEslService` — connects to FreeSWITCH ESL with background retry, subscribes to channel events, logs them. Key bugs fixed:

| # | Problem | Fix |
| :--- | :--- | :--- |
| 1 | `event_socket.conf.xml` used raw CIDR `0.0.0.0/0` for ACL | Changed to named ACL `"lan"` |
| 2 | ESL connect failure silently swallowed | Added background retry loop |
| 3 | `mod_loopback` only loaded dynamically | Added to `modules.conf.xml` |
| 4 | `ext-sip-ip` set to `auto-linklocal` (169.254.x.x) | Changed to `auto` |

---

### 11.5 Phase 4: Inbound Call Flow (Park → Route → Bridge → Hangup)
End-to-end inbound call handling with `FreeswitchCallSession` state machine:
```
PARKED → DIALING_AGENT → BRIDGED → COMPLETED
```

---

### 11.6 Security Hardening
- `acl.conf.xml`: Changed `default="deny"` with explicit private subnet allow rules.
- `event_socket.conf.xml`: Changed `apply-inbound-acl` from raw CIDR to named ACL `"lan"`.

---

### 11.7 Retry/Requeue Logic Refinement
- **Pre-answer call (`ROUTED`):** Requeueing is valid. Reset to `QUEUED`.
- **Active/bridged call (`IN_PROGRESS`):** Skip requeueing. Mark `FAILED`. Agent must remain `OFFLINE`.
- Added `AgentStateService` guard to prevent `OFFLINE` agents from being set back to `AVAILABLE` on `CALL_COMPLETED`.

---

### 11.8 Tenant-Level Isolation Audit & Fix Plan (Phases A–F)

**Root Problems Found:**
1. 🔴 Both Twilio SDK and JsSIP initialized simultaneously for every agent login.
2. 🔴 All 3 consumers receive every `routing-events` message — filtering by DB lookup only.
3. 🔴 `Call` entity had no `telephonyProvider` field.
4. 🟡 `RoutingEvent` had no `telephonyProvider` field.
5. 🟡 No tenant-level provider configuration existed.

**Fix Phases Implemented:**
- **Phase A:** Added `Tenant` entity, `telephonyProvider` in `AuthResponse`, tenant lookup on login.
- **Phase B:** Added `telephonyProvider` to `Call` entity and `RoutingEvent` DTO.
- **Phase C:** Added provider filter in both `telephony-service` and `freeswitch-service` consumers.
- **Phase D:** Frontend initializes only the correct SDK based on `localStorage.getItem('telephonyProvider')`.

---

### 11.9 Current Status

| Phase | Status | Description |
| :--- | :--- | :--- |
| Phase 0 | ✅ Done | Architecture decision — dedicated `freeswitch-service` |
| Phase 1 | ✅ Done | FreeSWITCH Docker container + XML config |
| Phase 2 | ✅ Done | `freeswitch-service` Spring Boot bootstrap |
| Phase 3 | ✅ Done | ESL connection with background retry and event logging |
| Phase 4 | ✅ Done | Inbound call: park → route → bridge → hangup propagation |
| Security | ✅ Done | ACL & ESL safety hardening (default-deny policy) |
| Retry Logic | ✅ Done | Active-call retry/requeue logic safely refined |
| Tenant Isolation | ✅ Done | Phases A–D complete — provider isolation end-to-end |
| **Phase 5** | 🔜 Next | Mid-call controls: hold, resume, recording, disconnect REST |
| **Phase 6** | 🔜 Next | Agent outbound dialing (click-to-call from frontend) |
| **Phase 7** | 🔜 Next | Integration tests and end-to-end validation |
