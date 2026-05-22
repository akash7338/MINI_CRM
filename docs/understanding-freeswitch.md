# FreeSWITCH + SIP + ESL + WebRTC Summary

---

# 1. What We Are Building

Originally the goal was:

```text
MiniGenesys
    |
FreeSWITCH
    |
PSTN Provider
```

Where FreeSWITCH behaves like:
- Twilio-style call engine
- PSTN bridge
- call controller

NOT:
- office PBX
- SIP extension platform

---

# 2. FreeSWITCH Architecture

FreeSWITCH internally is:

```text
FreeSWITCH Core
    +
Modules
```

Modules provide functionality.

Examples:

| Module | Purpose |
|---|---|
| mod_sofia | SIP/WebRTC engine |
| mod_event_socket | ESL control server |
| mod_dptools | answer/bridge/park |
| mod_commands | uuid_kill/originate/etc |
| mod_sndfile | recordings/audio |

---

# 3. modules.conf.xml

This file tells FreeSWITCH:

```text
Which modules to load.
```

Example:

```xml
<load module="mod_sofia"/>
```

means:

```text
Load SIP engine.
```

The module names are fixed FreeSWITCH module names.

---

# 4. XML Config Structure

Main file:

```text
freeswitch.xml
```

loads:
- module configs
- dialplans
- directories

using:

```xml
<X-PRE-PROCESS cmd="include".../>
```

---

# 5. What is X-PRE-PROCESS?

It means:

```text
Run preprocessing instruction before FreeSWITCH fully starts.
```

Examples:

| cmd | Meaning |
|---|---|
| include | load other XML files |
| set | define variables |

---

# 6. Sections

```xml
<section name="configuration">
```

means:
- module configs

```xml
<section name="dialplan">
```

means:
- call routing logic

```xml
<section name="directory">
```

means:
- SIP users/extensions

Section names are fixed FreeSWITCH concepts.

---

# 7. Directory

Directory normally stores:

```text
SIP users/extensions
```

like:
- 1001
- 1002
- passwords
- registrations

But our directory is intentionally empty because:

```text
We are NOT building a PBX.
```

We are building:
- PSTN bridge
- ESL-controlled architecture

---

# 8. SIP Softphones

A SIP softphone is:
- software behaving like a phone

Examples:
- Zoiper
- JsSIP
- Linphone

Normally softphones:
- REGISTER to FreeSWITCH
- authenticate via directory XML

---

# 9. Blind Registration

Current config:

```xml
accept-blind-reg=true
auth-calls=false
```

Meaning:

```text
Allow SIP/WebRTC registration without directory users/passwords.
```

So browser agents can register dynamically.

This is why:
- empty directory still works.

---

# 10. ACL (acl.conf.xml)

ACL means:

```text
Access Control List
```

Used like firewall rules.

Example:

```xml
<list name="lan" default="deny">
```

Controls:
- who may connect to ESL
- who may register SIP

---

# 11. ESL (Event Socket Library)

ESL is:

```text
Remote control interface for FreeSWITCH.
```

Java uses ESL to:
- originate calls
- bridge calls
- hangup
- record
- receive events

---

# 12. ESL Architecture

Inside FreeSWITCH:

```text
mod_event_socket
```

opens:
- TCP server
- port 8021

Java uses:
- java-esl-client

to connect.

---

# 13. Docker Networking

Docker mapping:

```yaml
8022:8021
```

means:

```text
Laptop localhost:8022
    ->
container port 8021
```

Docker internally forwards traffic to container IP automatically.

---

# 14. What Does 0.0.0.0 Mean?

Example:

```xml
<param name="listen-ip" value="0.0.0.0"/>
```

means:

```text
Listen on ALL network interfaces inside container.
```

This is:
- socket binding
- NOT advertisement.

Without it:
- service may listen only on container localhost
- Docker forwarding may fail.

---

# 15. Difference Between listen-ip and ACL

| Concept | Meaning |
|---|---|
| listen-ip | where server listens |
| ACL | who is allowed |

---

# 16. Sofia (mod_sofia)

This is the:
- SIP engine
- WebRTC engine
- RTP engine

Handles:
- SIP calls
- provider communication
- browser SIP/WebRTC
- RTP media

---

# 17. Profiles

Sofia uses profiles.

A profile is basically:

SIP server configuration.

# Internal vs External Sofia Profiles

Very important:

```text
internal/external
```

in FreeSWITCH are NOT literal network meanings.

They are TELEPHONY/PBX conventions.

---

# External Profile

External profile represents:

```text
outside telephony world
```

Examples:
- PSTN providers
- SIP trunks
- customer/public phone network
- carrier traffic

Typical flow:

```text
Customer Phone
      |
PSTN Provider
      |
FreeSWITCH external profile
```

So external means:

```text
carrier/public telephony side
```

NOT:
- literally outside Docker only.

---

# Internal Profile

Internal profile represents:

```text
inside organization/contact-center users
```

Examples:
- agents
- browser clients
- WebRTC softphones
- internal extensions

Typical flow:

```text
Agent Browser (JsSIP/WebRTC)
        |
FreeSWITCH internal profile
```

So browser is considered:

```text
internal contact-center endpoint
```

because:
- it belongs to your agents
- it belongs to your organization
- it is not PSTN/provider traffic

---

# Important Clarification

From pure networking perspective:

```text
Browser IS still outside FreeSWITCH.
```

It is:
- remote client
- external machine
- outside Docker container

BUT telephony systems classify it logically as:

```text
internal endpoint
```

because it belongs to:
- company agents
- internal users
- contact-center side

while providers/PSTN are classified as:
- external/public/carrier side.

---

# Simple Mental Model

| Profile | Telephony Meaning |
|---|---|
| external | PSTN/provider/public side |
| internal | agents/extensions/browser clients |

So:
- internal/external are TELEPHONY ROLES
- not literal network boundaries.

---

# 18. External Profile

Used for:
- PSTN providers
- SIP trunks
- customer calls

Handles:
- inbound PSTN
- outbound PSTN

---

# 19. Internal Profile

Used for:
- browser agents
- JsSIP
- WebRTC

This is where architecture shifted toward:
- browser softphone model.

---

# 20. Context

Example:

```xml
<param name="context" value="public"/>
```

means:

```text
Incoming calls should use dialplan context "public".
```

This maps to:

```xml
<context name="public">
```

inside:
- public.xml

---

# 21. Dialplan

Dialplan defines:

```text
What should happen when a call arrives?
```

Current dialplan:
- answer
- playback
- park

---

# 22. SIP

SIP is:

```text
call signaling protocol
```

Handles:
- INVITE
- BYE
- REGISTER
- ANSWER
- HOLD

SIP does NOT carry audio.

---

# 23. RTP

RTP carries:

```text
actual voice packets
```

So:

```text
SIP = signaling
RTP = media/audio
```

---

# 24. WebRTC

WebRTC provides:
- browser real-time audio/video
- microphone access
- NAT traversal
- encrypted media

WebRTC itself is NOT signaling.

It still needs:
- SIP
or another signaling mechanism.

---

# 25. Current Browser Architecture

Current flow:

```text
Browser (JsSIP/WebRTC)
    |
SIP over WSS
    |
FreeSWITCH internal profile
    |
RTP/SRTP media
```

---

# 26. SIP + WebRTC Relationship

In browser systems:

```text
SIP
=
signaling
```

```text
WebRTC
=
browser media framework
```

---

# 27. NAT

NAT means:

```text
private IP <-> public IP translation
```

Docker containers also use private IPs:
- 172.x.x.x

Outside systems cannot directly reach them.

---

# 28. ext-sip-ip

Example:

```xml
<param name="ext-sip-ip" value="auto"/>
```

means:

```text
What IP should FreeSWITCH advertise to OUTSIDE SIP systems?
```

Because providers cannot reach:
- Docker private IPs.

---

# 29. Difference Between sip-ip and ext-sip-ip

| Param | Meaning |
|---|---|
| sip-ip | where FreeSWITCH actually listens |
| ext-sip-ip | what IP FreeSWITCH tells outside systems to use |

---

# 30. ESL vs Sofia

Very important distinction:

| ESL | Sofia |
|---|---|
| control API | actual SIP engine |
| Java orchestration | telephony networking |
| commands/events | SIP/RTP execution |

---

# 31. Real Flow

```text
Java app
   |
ESL command
   |
FreeSWITCH
   |
mod_sofia
   |
actual SIP/RTP happens
```

ESL says:
- WHAT to do

Sofia actually:
- performs telephony work.

---

# 32. Current Major Architectural Shift

Originally the idea was:

```text
FreeSWITCH hidden behind backend
```

But current implementation evolved into:

```text
Browser directly registers to FreeSWITCH using WebRTC/SIP
```

That is why:
- internal profile
- WSS
- JsSIP
- blind registration
exist now.