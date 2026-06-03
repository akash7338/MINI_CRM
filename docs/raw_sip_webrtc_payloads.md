# Real SIP/SDP Payloads: Telnyx PSTN to WebRTC Browser

This document contains the **exact, real** SIP payloads captured from your FreeSWITCH server for the most recent successful call. They are organized chronologically into the two call legs.

---

## Part 1: Initial Registration (Opening the TCP Pinhole)
When FreeSWITCH starts up, it registers with Telnyx. This outbound TCP connection allows the router to dynamically open a port (in this case, 55040/65122) for inbound traffic to flow through without requiring manual port-forwarding.

### FreeSWITCH sends REGISTER to Telnyx
FreeSWITCH tells Telnyx its internal NAT-mapped Contact information.
```text
REGISTER sip:sip.telnyx.com;transport=tcp SIP/2.0
Via: SIP/2.0/TCP 152.58.133.7:55040;rport;branch=z9hG4bKt4tX4UBBKS8Ke
Max-Forwards: 70
From: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=HtDN2KBUS8t5N
To: <sip:usermailakashkrsingh64252@sip.telnyx.com>
Call-ID: 704ad766-7b92-4288-818b-3ffc3e661ac5
CSeq: 115675964 REGISTER
Contact: <sip:gw+telnyx@152.58.133.7:55040;transport=tcp;gw=telnyx>
Expires: 3600
User-Agent: FreeSWITCH-mod_sofia/1.10.12-release+git~20240802T210227Z~a88d069d6f~64bit
Authorization: Digest username="usermailakashkrsingh64252", realm="sip.telnyx.com", nonce="ah+pImofp/Zkc0zQJGR/zFX69LRHNI3O/KzKq0A=", opaque="115675964/10.231.83.184", algorithm=MD5, uri="sip:sip.telnyx.com;transport=tcp", response="db31f62e8cc1835c41b8c08220d7bc38"
Content-Length: 0
```
> [!NOTE] 
> **Key Fields Explained:**
> - **Contact**: FreeSWITCH explicitly instructs Telnyx: *"Save this IP and port in your routing database. When a call comes in for me, send it here."*

### Telnyx responds with 200 OK
Telnyx acknowledges the registration and detects the actual router port mapping.
```text
SIP/2.0 200 OK
Via: SIP/2.0/TCP 152.58.133.7:55040;received=152.58.133.7;rport=65122;branch=z9hG4bKt4tX4UBBKS8Ke
From: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=HtDN2KBUS8t5N
To: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=0871ced7e390d900ef2c9e82226e9a2f.6c370000
Call-ID: 704ad766-7b92-4288-818b-3ffc3e661ac5
CSeq: 115675964 REGISTER
Contact: <sip:gw+telnyx@152.58.133.7:55040;alias=152.58.133.7~65122~2;transport=tcp;gw=telnyx>;expires=3457
Server: Telnyx Registrar
```
> [!NOTE] 
> **Key Fields Explained:**
> - **Via**: Notice `rport=65122`. Telnyx detected that the router mapped the connection to external port 65122, even though FreeSWITCH claimed 55040.
> - **Contact alias**: Telnyx aliases the contact to `152.58.133.7~65122~2`. This allows Telnyx to route inbound `INVITE` calls straight back through your router's open pinhole, completely bypassing the firewall.

---

## Part 2: Telnyx <-> FreeSWITCH (The PSTN Leg)
This is a standard SIP over TCP leg. Telnyx sends the call to your server, and your server answers.

### 1. Telnyx sends INVITE to FreeSWITCH
Telnyx initiates the call, offering an SDP with its media IP (`50.114.144.48`).

```text
INVITE sip:12014269044@152.58.133.7:55040;transport=tcp;gw=telnyx SIP/2.0
Record-Route: <sip:64.16.250.10;transport=tcp;r2=on;lr;ftag=7y4SjHZ1Fer9D>
Record-Route: <sip:10.255.0.2;r2=on;lr;ftag=7y4SjHZ1Fer9D>
Record-Route: <sip:10.231.83.184:6050;lr;tnx=7c1.26b4>
Via: SIP/2.0/TCP 64.16.250.10;branch=z9hG4bKa5fb.cd6a5a07bbe709b919dd8775f1a61abb.0
Via: SIP/2.0/UDP 10.231.83.184:6050;rport=6050;branch=z9hG4bKa5fb.98890060b2f0d0aecad248f41a30c22a.0
v:SIP/2.0/UDP 10.231.91.24:6000;received=10.231.91.24;rport=6000;branch=z9hG4bKrgH3XvvDg2NZN
Max-Forwards:24
f:"919113162180"<sip:919113162180@sip.telnyx.com>;tag=7y4SjHZ1Fer9D
t:<sip:12014269044@10.231.83.184>
i:70f83b11-2dbe-47e9-8604-6afaa29efc7d
CSeq:115676495 INVITE
m:<sip:mod_sofia@10.231.91.24:6000>
Allow:INVITE,ACK,BYE,CANCEL,OPTIONS,MESSAGE,INFO,UPDATE,REFER,NOTIFY
k:timer,path
u:talk,hold,conference,refer
Privacy:none
c:application/sdp
Content-Disposition:session
l:555
X-Telnyx-Session-ID:e49f6b7e-5f03-11f1-8842-02420aef99a0
X-Telnyx-Leg-ID:e4a3f1a8-5f03-11f1-be2f-02420aef99a0
P-Asserted-Identity:"919113162180"<sip:919113162180@sip.telnyx.com;verstat=TN-Validation-Passed-C>

v=0
o=Telnyx 1780440687 1780440688 IN IP4 50.114.144.48
s=Telnyx
c=IN IP4 50.114.144.48
t=0 0
m=audio 19888 RTP/AVP 0 8 9 18 102 101 103
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=rtpmap:9 G722/8000
a=rtpmap:18 G729/8000
a=fmtp:18 annexb=no
a=rtpmap:102 opus/48000/2
a=fmtp:102 useinbandfec=1; maxaveragebitrate=30000; maxplaybackrate=48000; ptime=20; minptime=10; maxptime=40
a=rtpmap:101 telephone-event/8000
a=fmtp:101 0-15
a=rtpmap:103 telephone-event/48000
a=fmtp:103 0-15
a=sendrecv
a=rtcp:19889 IN IP4 50.114.144.48
a=ptime:20
```
> [!NOTE] 
> **Key Fields Explained:**
> - **Via**: Shows the route the packet took. Telnyx has internal proxies (`10.231.83.184`) and an edge proxy (`64.16.250.10`). Responses from FreeSWITCH must trace back through these Vias.
> - **f / t / i**: Short forms for `From`, `To`, and `Call-ID`. `f` is the caller (`919113162180`), `t` is your DID (`12014269044`).
> - **c=IN IP4 50.114.144.48**: Connection data. Telnyx is telling FreeSWITCH to send the RTP audio packets to this public IP.
> - **m=audio 19888 RTP/AVP 0 8 9 18 102 101 103**: Media description. Telnyx wants to receive audio on UDP port `19888`. It supports multiple codecs (PCMU `0`, PCMA `8`, Opus `102`, etc).

### 2. FreeSWITCH answers with 200 OK
FreeSWITCH accepts the call and provides its own SDP. Notice it uses your public IP (`152.58.133.7`) gathered via STUN.

```text
SIP/2.0 200 OK
Via: SIP/2.0/TCP 64.16.250.10;branch=z9hG4bKa5fb.cd6a5a07bbe709b919dd8775f1a61abb.0;rport=5060
Via: SIP/2.0/UDP 10.231.83.184:6050;rport=6050;branch=z9hG4bKa5fb.98890060b2f0d0aecad248f41a30c22a.0
v:SIP/2.0/UDP 10.231.91.24:6000;received=10.231.91.24;rport=6000;branch=z9hG4bKrgH3XvvDg2NZN
Record-Route: <sip:64.16.250.10;transport=tcp;r2=on;lr;ftag=7y4SjHZ1Fer9D>
Record-Route: <sip:10.255.0.2;r2=on;lr;ftag=7y4SjHZ1Fer9D>
Record-Route: <sip:10.231.83.184:6050;lr;tnx=7c1.26b4>
f:"919113162180"<sip:919113162180@sip.telnyx.com>;tag=7y4SjHZ1Fer9D
To: <sip:12014269044@10.231.83.184>;tag=X75QgtgptgFvK
i:70f83b11-2dbe-47e9-8604-6afaa29efc7d
CSeq:115676495 INVITE
Contact: <sip:12014269044@152.58.133.7:55040;transport=tcp>
User-Agent: FreeSWITCH-mod_sofia/1.10.12-release+git~20240802T210227Z~a88d069d6f~64bit
Accept: application/sdp
Allow: INVITE, ACK, BYE, CANCEL, OPTIONS, MESSAGE, INFO, UPDATE, REGISTER, REFER, NOTIFY
Supported: timer, path, replaces
Allow-Events: talk, hold, conference, refer
Content-Type: application/sdp
Content-Disposition: session
Content-Length: 254
P-Asserted-Identity: "12014269044" <sip:12014269044@10.231.83.184>

v=0
o=FreeSWITCH 1780377359 1780377360 IN IP4 152.58.133.7
s=FreeSWITCH
c=IN IP4 152.58.133.7
t=0 0
m=audio 56434 RTP/AVP 0 101
a=rtpmap:0 PCMU/8000
a=rtpmap:101 telephone-event/8000
a=fmtp:101 0-15
a=ptime:20
a=rtcp:56435 IN IP4 152.58.133.7
```
> [!NOTE]
> **Key Fields Explained:**
> - **Contact**: FreeSWITCH tells Telnyx "Send future requests for this call (like BYE or ACK) to my public IP `152.58.133.7` on TCP port `55040`".
> - **c=IN IP4 152.58.133.7**: FreeSWITCH tells Telnyx to send RTP audio to your public home IP.
> - **m=audio 56434 RTP/AVP 0 101**: FreeSWITCH opens UDP port `56434` for audio. It selected `PCMU` (G.711u) as the codec (the lowest common denominator for PSTN). 

---

## Part 3: FreeSWITCH <-> Browser (The WebRTC Leg)
Because WebRTC requires encryption, this leg operates over Secure WebSockets (WSS). The SDP is much larger because it includes cryptographic fingerprints and ICE candidates.

### 3. FreeSWITCH sends INVITE to the Browser
When Java bridges the call, FreeSWITCH dials the browser via WSS.

```text
INVITE sip:8t50q901@go3f8okl0f6s.invalid;transport=ws SIP/2.0
Via: SIP/2.0/WSS 172.18.0.2:7443;branch=z9hG4bK7S8ZKSrrjBtXc
Route: <sip:8t50q901@172.18.0.1:57802>;transport=wss
Max-Forwards: 70
From: "919113162180" <sip:919113162180@172.18.0.2>;tag=cQ5pUa4p9USDH
To: <sip:8t50q901@go3f8okl0f6s.invalid;transport=ws>
Call-ID: bc41978c-d9a6-123f-06be-0242ac120002
CSeq: 115676495 INVITE
Contact: <sip:mod_sofia@8.8.8.8:5064>
User-Agent: FreeSWITCH-mod_sofia/1.10.12-release+git~20240802T210227Z~a88d069d6f~64bit
Allow: INVITE, ACK, BYE, CANCEL, OPTIONS, MESSAGE, INFO, UPDATE, REGISTER, REFER, NOTIFY
Supported: timer, path, replaces
Allow-Events: talk, hold, conference, refer
Session-Expires: 120;refresher=uac
Min-SE: 120
Content-Type: application/sdp
Content-Disposition: session
Content-Length: 1148
X-FS-Support: update_display,send_info
Remote-Party-ID: "919113162180" <sip:919113162180@172.18.0.2>;party=calling;screen=yes;privacy=off

v=0
o=FreeSWITCH 1780417395 1780417396 IN IP4 172.18.0.2
s=FreeSWITCH
c=IN IP4 172.18.0.2
t=0 0
a=msid-semantic: WMS f6GWY7oEsLD2fub55BfO4tdIrOeJcSbL
m=audio 16398 RTP/SAVPF 102 0 8 103 104 101 13
a=rtpmap:102 opus/48000/2
a=fmtp:102 useinbandfec=0; cbr=1; maxaveragebitrate=30000; maxplaybackrate=48000; ptime=20; minptime=10; maxptime=40
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=rtpmap:103 telephone-event/48000
a=rtpmap:104 CN/48000
a=rtpmap:101 telephone-event/8000
a=rtpmap:13 CN/8000
a=fingerprint:sha-256 40:A8:27:68:5E:D0:50:37:BF:6B:A5:8A:98:2D:30:ED:C3:3F:1B:F6:40:54:63:56:8E:28:D5:8B:C7:00:52:5B
a=setup:actpass
a=rtcp-mux
a=rtcp:16398 IN IP4 172.18.0.2
a=ssrc:1713529367 cname:ZEUlyBSEuNEZ4aY3
a=ssrc:1713529367 msid:f6GWY7oEsLD2fub55BfO4tdIrOeJcSbL a0
a=ssrc:1713529367 mslabel:f6GWY7oEsLD2fub55BfO4tdIrOeJcSbL
a=ssrc:1713529367 label:f6GWY7oEsLD2fub55BfO4tdIrOeJcSbLa0
a=ice-ufrag:YwT5YdL6RR0CRF9P
a=ice-pwd:3C1fK0epldIjZdX1xRDSikcx
a=candidate:8594072118 1 udp 2130706431 172.18.0.2 16398 typ host generation 0
a=candidate:8594072118 2 udp 2130706431 172.18.0.2 16398 typ host generation 0
a=ptime:20
```
> [!NOTE]
> **WebRTC-Specific Fields Explained:**
> - **RTP/SAVPF**: Stands for Secure Audio Video Profile with Feedback. WebRTC mandates this; regular SIP (like Telnyx) just uses `RTP/AVP`.
> - **a=fingerprint**: A cryptographic hash of FreeSWITCH's DTLS certificate. The browser verifies this to prevent man-in-the-middle attacks.
> - **a=setup:actpass**: Dictates who initiates the DTLS handshake. `actpass` means FreeSWITCH lets the browser decide.
> - **a=candidate**: ICE (Interactive Connectivity Establishment) candidates. FreeSWITCH offers its Docker internal IP (`172.18.0.2`) on port `16398` to try to establish a peer-to-peer UDP connection.
> - **Contact**: Notice how `external_sip_ip` is still set to `8.8.8.8` here! But because `external_rtp_ip` was removed, the critical `c=` and `a=candidate` lines correctly resolved to `172.18.0.2`.

### 4. Browser answers with 200 OK
When you click "Answer" in the browser UI, JsSIP generates the answering SDP.

```text
SIP/2.0 200 OK
Via: SIP/2.0/WSS 172.18.0.2:7443;branch=z9hG4bK7S8ZKSrrjBtXc
To: <sip:8t50q901@go3f8okl0f6s.invalid;transport=ws>;tag=vnni6r6mu9
From: "919113162180" <sip:919113162180@172.18.0.2>;tag=cQ5pUa4p9USDH
Call-ID: bc41978c-d9a6-123f-06be-0242ac120002
CSeq: 115676495 INVITE
Contact: <sip:8t50q901@go3f8okl0f6s.invalid;transport=ws>
Session-Expires: 120;refresher=uac
Supported: timer,ice,replaces,outbound
Content-Type: application/sdp
Content-Length: 1037

v=0
o=- 7000358842306954110 2 IN IP4 127.0.0.1
s=-
t=0 0
a=msid-semantic: WMS 3d5d744c-cce6-46d6-bb99-d6f4e5e49536
m=audio 63816 RTP/SAVPF 102 0 8 103 101 13
c=IN IP4 192.168.31.143
a=rtcp:9 IN IP4 0.0.0.0
a=candidate:702739411 1 udp 2122260223 192.168.31.143 63816 typ host generation 0 network-id 1 network-cost 10
a=candidate:1462571339 1 tcp 1518280447 192.168.31.143 9 typ host tcptype active generation 0 network-id 1 network-cost 10
a=ice-ufrag:ZVyK
a=ice-pwd:Dg5H4klHRp0PkmRCdzHXYwtE
a=ice-options:trickle
a=fingerprint:sha-256 88:3F:DD:77:28:CB:35:0F:CA:D0:B9:B6:EC:83:DB:A8:FA:31:77:3C:D7:ED:97:6E:5E:6A:80:9B:29:EF:69:13
a=setup:active
a=mid:0
a=sendrecv
a=rtcp-mux
a=rtpmap:102 opus/48000/2
a=fmtp:102 minptime=10;useinbandfec=1
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=rtpmap:103 telephone-event/48000
a=rtpmap:101 telephone-event/8000
a=rtpmap:13 CN/8000
a=ssrc:4033464433 cname:BkLucnbcvU7ne2Ur
a=ssrc:4033464433 msid:3d5d744c-cce6-46d6-bb99-d6f4e5e49536 45724f4a-f218-4884-8b24-1f15c945ffe4
```
> [!NOTE]
> **Key Fields Explained:**
> - **c=IN IP4 192.168.31.143**: The browser exposes your Mac's true LAN IP address to establish the UDP path.
> - **a=candidate**: The browser offers a UDP candidate (`192.168.31.143:63816`) and a TCP fallback candidate. FreeSWITCH selects the UDP candidate, establishing a direct connection between `172.18.0.2` (Docker) and `192.168.31.143` (Mac host).
> - **a=setup:active**: The browser takes the active role, firing off the initial DTLS handshake packets to FreeSWITCH.

---

## Part 4: Teardown (BYE)
When you manually hung up the smartphone, Telnyx sent a BYE to FreeSWITCH.

```text
BYE sip:12014269044@152.58.133.7:65122;transport=tcp SIP/2.0
Via: SIP/2.0/TCP 64.16.250.10;branch=z9hG4bK75fb.93444a9a15d92ba2a7e70cc97309802b.0
Via: SIP/2.0/UDP 10.231.83.184:6050;rport=6050;branch=z9hG4bK75fb.e65bbd68f8f59c7387aa6f921db7c6de.0
v:SIP/2.0/UDP 10.231.91.24:6000;received=10.231.91.24;rport=6000;branch=z9hG4bKy68Q8y12yQUFB
Max-Forwards:68
f:"919113162180"<sip:919113162180@sip.telnyx.com>;tag=7y4SjHZ1Fer9D
t:<sip:12014269044@10.231.83.184>;tag=X75QgtgptgFvK
i:70f83b11-2dbe-47e9-8604-6afaa29efc7d
CSeq:115676496 BYE
Allow:INVITE,ACK,BYE,CANCEL,OPTIONS,MESSAGE,INFO,UPDATE,REFER,NOTIFY
k:timer,path
Reason:Q.850;cause=16;text="NORMAL_CLEARING"
l:0
```
> [!NOTE]
> **Key Fields Explained:**
> - **Reason:Q.850;cause=16**: The most important field in a disconnect trace. `cause=16` maps to `NORMAL_CLEARING` in the Q.850 standard, which explicitly means the caller hung up the phone normally. If there was a network timeout or failure, you would see a `cause=41` or similar error. 

FreeSWITCH then propagated this exact `BYE` downstream over WebSockets to your browser to tear down the WebRTC leg.

---

## Part 5: The "Two Different Worlds" of FreeSWITCH NAT
During our analysis, we uncovered a critical insight into how FreeSWITCH handles NAT translation differently for SIP signaling vs. WebRTC media.

When analyzing the payloads, you will notice that the `REGISTER` payload advertises your public WAN IP (`152.58.133.7`), while the WebRTC `SDP` advertises your local Docker IP (`172.18.0.2`). This feels inconsistent, but it is actually exactly how FreeSWITCH's dual-profile architecture is designed to handle edge-routing:

### 1. SIP Signaling NAT Policy (The External Profile)
- **Uses**: `external` Sofia profile (Telnyx/PSTN)
- **Runtime State**: `SIP-IP = 172.18.0.2`, `Ext-SIP-IP = 152.58.133.7`
- **Behavior**: The `external` profile knows it is talking to the public internet. Therefore, Sofia explicitly rewrites the `Contact`, `Via`, and `Record-Route` headers using the `Ext-SIP-IP`. 
- **Result**: The `REGISTER` payload correctly advertises `152.58.133.7` so that Telnyx knows exactly where to send future inbound `INVITE` requests over the established TCP pinhole.

### 2. WebRTC Media NAT Policy (The Internal Profile)
- **Uses**: `internal` Sofia profile (WebRTC/Browser)
- **Runtime State**: `RTP-IP = 172.18.0.2`
- **Behavior**: The `internal` profile believes it is talking to local endpoints. WebRTC media candidates are generated by a completely different subsystem (the ICE engine and RTP ACL logic) which overrides standard SIP NAT rules.
- **Result**: It intentionally injects raw local host candidates (`172.18.0.2`) directly into the SDP (`c=` and `a=candidate` lines), allowing WebRTC's peer-to-peer ICE negotiation to dynamically establish the actual media path with your browser.

It isn't one NAT mechanism behaving differently—it's two different FreeSWITCH subsystems making independent decisions. The external Sofia gateway profile does the right thing for Telnyx, while the internal WebRTC profile intentionally advertises local candidates for your browser.
