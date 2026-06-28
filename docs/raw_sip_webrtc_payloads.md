# Real SIP/SDP Payloads: Telnyx PSTN to WebRTC Browser

This document contains the **exact, real** SIP payloads captured from your FreeSWITCH server for the most recent successful call. They are organized chronologically into the two call legs.

> [!IMPORTANT]
> **Configuration Context (Parts 1-5): The "Best Practice" Setup**
> The payloads in Parts 1 through 5 were captured with the following active configuration:
> - **External Profile (Telnyx/PSTN):** STUN was explicitly enabled (`<param name="ext-sip-ip" value="stun:..."/>`). This forces FreeSWITCH to accurately write the public WAN IP into the SIP text.
> - **Internal Profile (WebRTC):** `ext-rtp-ip` was set to local (`$${local_ip_v4}` / `172.18.0.2`), intentionally disabling STUN for media so that the browser could route audio locally over the LAN/Docker bridge.
> 
> *(For payloads captured under broken or misconfigured states, see Part 6 at the bottom of this document).*

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
> - **Via**: This is the immediate transaction return path for this specific `REGISTER` request. Telnyx uses `Via` (plus `rport`/`received`) to send the `200 OK` response for this request right now.
> - **Contact**: FreeSWITCH explicitly instructs Telnyx: *"Save this IP and port in your routing database. When a call comes in for me, send it here."*
> - **Via vs Contact in REGISTER (critical):** `Via` = *reply to this current transaction now*; `Contact` = *reach me later for future inbound requests* (for example, a new inbound `INVITE` to your DID).

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
> - **Which request is this `200 OK` answering?** This is the response to the `REGISTER` shown just above. You can confirm by matching `Call-ID` and `CSeq: ... REGISTER`.
> - **What stays same from request to response?** `Call-ID`, `CSeq` method (`REGISTER`), and transaction `branch` stay aligned so both sides know this reply belongs to the same transaction.
> - **What changes in the response?** Telnyx adds what it actually observed on the wire (`received` and `rport`) and returns registrar metadata (`Server`, `expires`).
> - **Via**: Notice `rport=65122`. Telnyx detected that the router mapped the connection to external port 65122, even though FreeSWITCH claimed 55040.
> - **Contact alias**: Telnyx aliases the contact to `152.58.133.7~65122~2`. This allows Telnyx to route inbound `INVITE` calls straight back through your router's open pinhole, completely bypassing the firewall.

---

## Part 2: Telnyx <-> FreeSWITCH (The PSTN Leg)
This is a standard SIP over TCP leg. Telnyx sends the call to your server, and your server answers.

### 1. Telnyx sends INVITE to FreeSWITCH
Telnyx initiates the call, offering an SDP with its media IP (`50.114.144.48`).

> [!NOTE]
> **Why does the INVITE Request-URI show port `55040` and not `65122`?**
> - FreeSWITCH registered with `Contact: <sip:gw+telnyx@152.58.133.7:55040>` during REGISTER — that's what Telnyx stored.
> - So the INVITE Request-URI copies that Contact value: `INVITE sip:12014269044@152.58.133.7:55040`.
> - However, Telnyx does **not** blindly send the packet to port `55040`. It uses the `alias=152.58.133.7~65122~2` it learned from the REGISTER 200 OK to route the actual TCP packet to `152.58.133.7:65122`.
> - The router receives it on `65122`, maps it back to FreeSWITCH's internal `55040`, and delivers it.
> - So `55040` in the Request-URI is what FreeSWITCH advertised (cosmetic/SIP-layer). `65122` is what Telnyx actually uses for delivery (transport-layer).

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
> - **Via (plain meaning):** Think of `Via` as a "return route list" attached to this request. Every middle server that forwards this INVITE adds one `Via` line. Later, when FreeSWITCH sends `100 Trying`, `180 Ringing`, or `200 OK`, those responses follow the same path back in reverse order.
> - **What is a SIP proxy in this context?** A SIP proxy is a Telnyx middle server for call-control messages. It receives the INVITE, checks Telnyx rules (routing, security, fraud checks, policy), adds its own `Via`, and forwards to the next Telnyx server. It is for signaling control flow; it is not your RTP voice path.
> - **What is a hop?** One hop means one server-to-server jump. So if you see 3 `Via` lines, this INVITE passed through 3 signaling servers before reaching your FreeSWITCH.
> - **Why does Telnyx use multiple hops?** Carriers run many servers, not one box. They split work across edge nodes and internal nodes for load sharing, failover, stability, security filtering, and regional routing decisions. This is normal carrier behavior.
> - **Why not use just one "originator" address?** In a carrier network, there are multiple middle servers that must stay in the signaling path for policy, security, and transaction state. A single return address would skip those servers and can break NAT-safe return routing, proxy state tracking, and failover logic. The `Via` stack keeps return routing correct hop-by-hop.
> - **Who does FreeSWITCH send `200 OK` to?** FreeSWITCH always sends it to the first (top) `Via` line only. That server removes its own step from the list and forwards to the next one. This repeats until the response reaches the server that started this transaction on Telnyx side.
> - **Simple delivery-hub analogy:** You do not hand a parcel directly to the final warehouse manager. You hand it to the nearest courier center, then it moves center-by-center internally. `Via` is that center list.
> - **Applied to this exact INVITE:** FreeSWITCH first responds to `64.16.250.10` (edge Telnyx server). Then Telnyx forwards internally via `10.231.83.184` and `10.231.91.24` until it reaches the internal owner for this call transaction.
> - **`f / t / i` headers:** These are short header names. `f` = `From` (caller identity), `t` = `To` (called identity), `i` = `Call-ID` (unique ID used to correlate all signaling messages for this leg).
> - **What does the IP inside `t:` mean?** In `t:<sip:12014269044@10.231.83.184>`, that IP is Telnyx's internal signaling context where this called identity is currently represented. It is not the transport destination FreeSWITCH used for this hop. The actual hop destination is on the Request-URI line: `INVITE sip:...@152.58.133.7:55040`.
> - **What does `m:` mean here?** `m` is the short form of SIP `Contact`. In this INVITE, `m:<sip:mod_sofia@10.231.91.24:6000>` means "for later messages in this same established call leg (like `BYE` or re-INVITE), this is the contact URI Telnyx advertises." The text `mod_sofia` here is just a label string in the SIP URI, not your local FreeSWITCH module.
> - **`c=IN IP4 50.114.144.48`:** This is the media connection IP from Telnyx SDP. It tells FreeSWITCH where Telnyx expects RTP audio for this leg.
> - **`m=audio 19888 RTP/AVP ...`:** This is the media port + codec offer from Telnyx. Port `19888` is where Telnyx expects RTP for this call leg, and the codec list shows what formats Telnyx can decode.
> - **Why does Request-URI show `55040` but Telnyx actually delivered to `65122`?** The Request-URI (`INVITE sip:12014269044@152.58.133.7:55040`) is copied from the `Contact` FreeSWITCH registered. Telnyx does not use that port for actual delivery — it uses `alias=152.58.133.7~65122~2` (saved from the REGISTER 200 OK) to send the packet to `65122`. The router maps `65122 → 55040` and delivers it to FreeSWITCH. So `55040` is the SIP-layer advertised port; `65122` is the transport-layer delivery port.
> - **`P-Asserted-Identity` and `verstat=TN-Validation-Passed-C`:** This is STIR/SHAKEN — a standard to verify caller ID is legitimate and not spoofed. `verstat=TN-Validation-Passed-C` means the calling number `919113162180` was verified by Telnyx with attestation level C. Levels: `A` = full attestation (carrier knows customer AND number is theirs), `B` = partial (carrier knows customer but not number ownership), `C` = gateway (call entered Telnyx network but number ownership not verified). Level C is normal for international calls coming through a gateway.

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
> - **Which request is this `200 OK` answering?** It answers the PSTN `INVITE` from Part 2 Step 1. You can verify with matching `Call-ID` and `CSeq: ... INVITE`.
> - **What this response does in plain terms:** FreeSWITCH says "Call accepted" and sends its SDP answer (`c=` + `m=audio`) telling Telnyx where to send RTP.
> - **What stays same from request to response?** `Call-ID` and `CSeq` transaction identity stay tied to the original INVITE so Telnyx can bind this answer to the correct ringing call.
> - **What changes in the response?** FreeSWITCH now provides its own reachable signaling/media targets (`Contact`, SDP IP/port) and selected codec subset.
> - **Why `200 OK` repeats `Via` lines (detailed):**
>   1. When `INVITE` travels forward, every proxy adds one `Via` line.
>   2. Example flow:
>      - Proxy A adds `Via A`
>      - Proxy B adds `Via B`
>      - Proxy C adds `Via C`
>      - FreeSWITCH receives INVITE with stack: `[Via A, Via B, Via C]` (top line first)
>   3. Now FreeSWITCH must return `200 OK`, but it must return on the exact same signaling path.
>   4. To do that, it reuses the same `Via` stack from the INVITE as transaction return context.
> - **What "pop hop-by-hop" means (detailed):**
>   1. FreeSWITCH sends `200 OK` to the **top `Via` target** (nearest previous proxy).
>   2. That proxy recognizes its own `Via` entry and forwards response to the next `Via`.
>   3. It consumes/removes its own step (conceptually "pop").
>   4. Next proxy does the same.
>   5. This repeats until response reaches the originator side.
>   6. So the response walks backward through the same chain the INVITE used forward.
> - **Why SIP is designed this way:**
>   - Without reusing `Via`, responses would need to guess return route.
>   - Multi-proxy carrier networks (like Telnyx) would fail frequently.
>   - Transaction matching would become unreliable.
>   - `Via + branch` together make response routing deterministic and transaction-safe.
> - **Important distinction:** In a response, `Via` is **not** a new destination instruction. It is the already-built return breadcrumb list from the original request transaction. That is why it looks "copied"—it is supposed to be echoed for correctness.
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
> - **Which request is this `200 OK` answering?** It answers FreeSWITCH's WebRTC-side `INVITE` from Part 3 Step 3. Confirm by matching `Call-ID` and `CSeq: ... INVITE`.
> - **What this response does in plain terms:** Browser says "I accepted the call" and returns its SDP answer with local ICE candidates and media port.
> - **What stays same from request to response?** `Call-ID`, `From/To` dialog identity (with tag update), and `CSeq` method stay linked so FreeSWITCH attaches this answer to the correct browser leg.
> - **What changes in the response?** Browser now contributes its own media details (`c=`, `m=audio`, candidates, DTLS fingerprint role) so both sides can run ICE/DTLS and start SRTP.
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

---

## Part 6: NAT Resiliency Experiments (Breaking STUN)
To fully understand how FreeSWITCH and Telnyx handle NAT, we performed two deliberate tests to "break" the `ext-sip-ip` STUN configuration and observe the resulting SIP payloads.

### Experiment 1: Removing STUN Completely
We commented out the STUN configuration (`ext-sip-ip`) from the `external` profile to see if FreeSWITCH would fail.

**The Outbound REGISTER Payload Sent:**
```text
REGISTER sip:sip.telnyx.com;transport=tcp SIP/2.0
Via: SIP/2.0/TCP 152.58.133.7:55040;rport;branch=z9hG4bK71Ba29yZ9NHSa
Contact: <sip:gw+telnyx@152.58.133.7:55040;transport=tcp;gw=telnyx>
```
> [!NOTE]
> **Key Fields Explained:**
> - **Expected Result**: We expected FreeSWITCH to blindly inject the local Docker IP (`172.18.0.2`) into the `Via` and `Contact` headers since STUN was disabled.
> - **Actual Result**: FreeSWITCH *still* correctly injected the public WAN IP (`152.58.133.7`).
> - **Why?**: Because the STUN configuration was missing, FreeSWITCH's core `autonat` feature took over. Since it knew the `external` profile was meant for public internet traffic, it automatically used UPnP/NAT-PMP or its own internal IP cache to detect the public IP and injected it anyway. FreeSWITCH proactively refused to send a broken local IP to the public internet.

### Experiment 2: Forcing the Local Docker IP
To truly bypass `autonat` and force a failure, we hardcoded `<param name="ext-sip-ip" value="$${local_ip_v4}"/>` (which resolves to `172.18.0.2`). FreeSWITCH was now completely blind to its public IP.

**The "Broken" REGISTER Payload Sent:**
FreeSWITCH blindly wrote its internal Docker IP into the payload.
```text
REGISTER sip:sip.telnyx.com;transport=tcp SIP/2.0
Via: SIP/2.0/TCP 172.18.0.2;rport;branch=z9hG4bKHgmFmmpKXarZB
Contact: <sip:gw+telnyx@172.18.0.2:5060;transport=tcp;gw=telnyx>
```
> [!WARNING]
> **Key Fields Explained:**
> - **Via / Contact**: Both headers contain the private, unroutable Docker IP (`172.18.0.2`). If a legacy carrier (like an old Avaya PBX) received this, they would attempt to send the SIP response back to `172.18.0.2` and the call would instantly drop.

**The 200 OK Response from Telnyx (The Savior):**
```text
SIP/2.0 200 OK
Via: SIP/2.0/TCP 172.18.0.2:5060;received=172.18.0.2;rport=51201;branch=z9hG4bKjSD8NF7ptKejQ
Contact: <sip:gw+telnyx@172.18.0.2:5060;alias=152.58.133.7~51201~2;transport=tcp;gw=telnyx>;expires=3481
```
> [!TIP]
> **How the Connection Survived (Symmetric Routing):**
> 0. **Which request is this `200 OK` answering?** It answers the intentionally broken `REGISTER` shown right above (`CSeq ... REGISTER` + same transaction branch family).
> 1. **The physical NAT envelope:** When the packet left the home network, the router translated the physical TCP/IP headers to Source IP `152.58.133.7` and Source Port `51201`.
> 2. **The `rport` flag:** FreeSWITCH included the empty `;rport` flag on the outbound `Via` header. This strict protocol instruction told Telnyx to ignore the IPs written in the text and exclusively route replies to the physical envelope address.
> 3. **The Alias mechanism:** Telnyx read the physical NAT envelope (`152.58.133.7:51201`), ignored the `Contact` header text entirely, and explicitly saved an `alias=152.58.133.7~51201~2` tag. All future inbound calls from Telnyx were successfully routed to this public alias instead of the broken text IP!

**Conclusion:** While STUN is best-practice for ensuring the text perfectly matches the NAT envelope (satisfying strict legacy PBXs), `rport` and Symmetric Routing act as an indestructible safety net for modern SIP carriers.

---

## Part 7: No-STUN External Profile Test Capture (Fresh Call)

This section captures a fresh test call made after disabling external STUN params in `sofia.conf.xml`:
- `ext-sip-ip` removed/commented
- `ext-rtp-ip` removed/commented

These payloads were captured from the live FreeSWITCH container logs during the test call window.

### A) Telnyx INVITE to FreeSWITCH (External Leg, No STUN)
```text
INVITE sip:12014269044@172.18.0.2:5060;transport=tcp;gw=telnyx SIP/2.0
Record-Route: <sip:64.16.250.10;transport=tcp;lr;r2=on;ftag=pFcB8X85g7Q2H>
Record-Route: <sip:10.255.0.2;lr;r2=on;ftag=pFcB8X85g7Q2H>
Record-Route: <sip:10.231.83.184:6050;lr;tnx=ee1.24c4>
Via: SIP/2.0/TCP 64.16.250.10;branch=z9hG4bK8543.725fdc15c593993de236795baf6b375c.0
From: "919113162180" <sip:919113162180@sip.telnyx.com>;tag=pFcB8X85g7Q2H
To: <sip:12014269044@10.231.83.184>
Call-ID: 30d285e9-a4ec-4840-80f8-4ef0d6007f27
CSeq: 115914863 INVITE
Contact: <sip:mod_sofia@10.239.132.204:6000>
... (SDP omitted for brevity)
```

> [!NOTE]
> **What this proves:**
> - Telnyx routed the call to `172.18.0.2:5060` in Request-URI for this test.
> - This is exactly the "local/docker IP in external signaling" behavior expected when STUN is removed and external profile advertises local context.

### B) FreeSWITCH 200 OK to Telnyx (External Leg, No STUN)
```text
SIP/2.0 200 OK
Via: SIP/2.0/TCP 64.16.250.10;branch=z9hG4bK8543.725fdc15c593993de236795baf6b375c.0;rport=5060
From: "919113162180" <sip:919113162180@sip.telnyx.com>;tag=pFcB8X85g7Q2H
To: <sip:12014269044@10.231.83.184>;tag=HmFQ8rrr4vSem
Call-ID: 30d285e9-a4ec-4840-80f8-4ef0d6007f27
CSeq: 115914863 INVITE
Contact: <sip:12014269044@172.18.0.2:5060;transport=tcp>
... (SDP omitted for brevity)
```

> [!NOTE]
> **What this proves:**
> - FreeSWITCH answered with `Contact` set to `172.18.0.2:5060` (external leg).
> - This confirms external signaling advertisement remained local/private during the no-STUN test.

### C) FreeSWITCH BYE to Telnyx (External Leg Teardown)
```text
BYE sip:mod_sofia@10.239.132.204:6000 SIP/2.0
Via: SIP/2.0/TCP 172.18.0.2;branch=z9hG4bKK6ySX68gm1rZr
Route: <sip:64.16.250.10;transport=tcp;lr;r2=on;ftag=pFcB8X85g7Q2H>
Route: <sip:10.255.0.2;lr;r2=on;ftag=pFcB8X85g7Q2H>
Route: <sip:10.231.83.184:6050;lr;tnx=ee1.24c4>
From: <sip:12014269044@10.231.83.184>;tag=HmFQ8rrr4vSem
To: "919113162180" <sip:919113162180@sip.telnyx.com>;tag=pFcB8X85g7Q2H
Call-ID: 30d285e9-a4ec-4840-80f8-4ef0d6007f27
CSeq: 115914874 BYE
```

> [!NOTE]
> **What this proves:**
> - Teardown signaling for this same dialog also continued to use local/docker identity in `Via`.
> - Even with local/private signaling identity, call completed because carrier-side proxy/routing logic preserved dialog path and NAT-aware return behavior.

---

## Part 8: External Profile Mode Comparison (Same 4 Key Lines)

This is a quick side-by-side reference using the same 4 fields for each mode:
1) INVITE Request-URI target  
2) FreeSWITCH 200 OK Contact  
3) FreeSWITCH SDP `c=` IP  
4) FreeSWITCH BYE Via identity

### Mode A: STUN (`ext-sip-ip=stun`, `ext-rtp-ip=stun`)
```text
INVITE sip:12014269044@152.58.133.7:55040;transport=tcp;gw=telnyx SIP/2.0
Contact: <sip:12014269044@152.58.133.7:55040;transport=tcp>
c=IN IP4 152.58.133.7
BYE sip:12014269044@152.58.133.7:65122;transport=tcp SIP/2.0
```
**Interpretation:** Public/WAN-facing signaling + media identity in SIP/SDP text.

### Mode B: `auto` (runtime observed as local/docker identity in this environment)
```text
INVITE sip:12014269044@172.18.0.2:5060;transport=tcp;gw=telnyx SIP/2.0
Contact: <sip:12014269044@172.18.0.2:5060;transport=tcp>
c=IN IP4 172.18.0.2
Via: SIP/2.0/TCP 172.18.0.2;rport;branch=z9hG4bKjU7D5a99Sre6m
```
**Interpretation:** In this Docker setup, `auto` produced local/docker-advertised identity for external leg payloads.

### Mode C: No params removed (`ext-sip-ip` and `ext-rtp-ip` commented)
```text
INVITE sip:12014269044@172.18.0.2:5060;transport=tcp;gw=telnyx SIP/2.0
Contact: <sip:12014269044@172.18.0.2:5060;transport=tcp>
c=IN IP4 172.18.0.2
Via: SIP/2.0/TCP 172.18.0.2;branch=z9hG4bKK6ySX68gm1rZr
```
**Interpretation:** External leg also advertises local/docker identity in payload text.

> [!IMPORTANT]
> **Evidence-based takeaway from these captures:**
> - STUN mode advertised public IP in external SIP/SDP text.
> - Both `auto` and no-params captures advertised docker/local IP in this environment.
> - Calls still completed due carrier-side NAT-aware behavior (`rport`, alias/symmetric routing), but this is less deterministic than explicit STUN.

---

## Part 9: Complete External-Leg Payload Comparison (Full Blocks)

Below are complete external-leg SIP payload blocks for each mode in real call order:
`REGISTER` -> `200 OK (REGISTER)` -> `INVITE` -> `200 OK (INVITE)` -> `BYE`.

### Mode A: STUN (`ext-sip-ip=stun`, `ext-rtp-ip=stun`)

#### A0) REGISTER (FreeSWITCH -> Telnyx)
```text
REGISTER sip:sip.telnyx.com;transport=tcp SIP/2.0
Via: SIP/2.0/TCP 223.181.29.166:16776;rport;branch=z9hG4bKpZUBZQQmF0SKp
From: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=Kty68m6r747Fj
To: <sip:usermailakashkrsingh64252@sip.telnyx.com>
Call-ID: 3a14eae1-7045-427b-b4a2-dcb2c18774eb
CSeq: 115916598 REGISTER
Contact: <sip:gw+telnyx@223.181.29.166:16776;transport=tcp;gw=telnyx>
Expires: 3600
Authorization: Digest username="usermailakashkrsingh64252", realm="sip.telnyx.com", nonce="aicBGGom/+zecxeAoFibnI6BdnB2oizAXVw62UA=", opaque="115916598/10.13.246.184", algorithm=MD5, uri="sip:sip.telnyx.com;transport=tcp", response="30d961277f9dacf4010d99bd355cedca"
Content-Length: 0
```

#### A0b) 200 OK to REGISTER (Telnyx -> FreeSWITCH)
```text
SIP/2.0 200 OK
Via: SIP/2.0/TCP 223.181.29.166:16776;rport=32135;received=223.181.29.166;branch=z9hG4bKpZUBZQQmF0SKp
From: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=Kty68m6r747Fj
To: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=eb51cce0171c859d37e55fe95de13457-6c370000
Call-ID: 3a14eae1-7045-427b-b4a2-dcb2c18774eb
CSeq: 115916598 REGISTER
Contact: <sip:gw+telnyx@223.181.29.166:16776;transport=tcp;alias=223.181.29.166~32135~2;gw=telnyx>;expires=3548
Server: Telnyx Registrar
Content-Length: 0
```

#### A1) INVITE (Telnyx -> FreeSWITCH)
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

#### A2) 200 OK (FreeSWITCH -> Telnyx)
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

#### A3) BYE (Telnyx -> FreeSWITCH)
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

### Mode B: `auto` (runtime behaved as docker/local in this environment)

#### B0) REGISTER (FreeSWITCH -> Telnyx)
```text
REGISTER sip:sip.telnyx.com;transport=tcp SIP/2.0
Via: SIP/2.0/TCP 172.18.0.2;rport;branch=z9hG4bKSH3BmrN8re7KB
From: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=p7KQjHXt43K5r
To: <sip:usermailakashkrsingh64252@sip.telnyx.com>
Call-ID: 394c0da8-6bd0-4172-b64a-9bed7c4d9580
CSeq: 115916633 REGISTER
Contact: <sip:gw+telnyx@172.18.0.2:5060;transport=tcp;gw=telnyx>
Expires: 3600
Authorization: Digest username="usermailakashkrsingh64252", realm="sip.telnyx.com", nonce="aicBXGonADCBQvqh9cU+etaIr7XRhk8NXXQWnUA=", opaque="115916633/10.13.246.184", algorithm=MD5, uri="sip:sip.telnyx.com;transport=tcp", response="b847945971816995a9a0d89d35e42d0c"
Content-Length: 0
```

#### B0b) 200 OK to REGISTER (Telnyx -> FreeSWITCH)
```text
SIP/2.0 200 OK
Via: SIP/2.0/TCP 172.18.0.2;rport=18556;received=223.181.29.166;branch=z9hG4bKSH3BmrN8re7KB
From: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=p7KQjHXt43K5r
To: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=eb51cce0171c859d37e55fe95de13457-6c370000
Call-ID: 394c0da8-6bd0-4172-b64a-9bed7c4d9580
CSeq: 115916633 REGISTER
Contact: <sip:gw+telnyx@172.18.0.2:5060;transport=tcp;alias=223.181.29.166~18556~2;gw=telnyx>;expires=3584
Server: Telnyx Registrar
Content-Length: 0
```

#### B1) INVITE (Telnyx -> FreeSWITCH)
```text
INVITE sip:12014269044@172.18.0.2:5060;transport=tcp;gw=telnyx SIP/2.0
Record-Route: <sip:64.16.250.10;transport=tcp;lr;r2=on;ftag=D3KcKcFt09Zac>
Record-Route: <sip:10.255.0.2;lr;r2=on;ftag=D3KcKcFt09Zac>
Record-Route: <sip:10.13.246.184:6050;lr;tnx=392.bd05>
Via: SIP/2.0/TCP 64.16.250.10;branch=z9hG4bKebf3.7b2d4ead6e6cf75b42794ab0df27c685.0
Max-Forwards: 62
From: "919113162180" <sip:919113162180@sip.telnyx.com>;tag=D3KcKcFt09Zac
To: <sip:12014269044@10.13.246.184>
Call-ID: 2176f6ce-9194-4479-885f-ad48f631188f
CSeq: 115915707 INVITE
Contact: <sip:mod_sofia@10.239.45.204:6000>
Allow: INVITE,ACK,BYE,CANCEL,OPTIONS,MESSAGE,INFO,UPDATE,REFER,NOTIFY
Supported: timer,path
u: talk,hold,conference,refer
Privacy: none
Content-Disposition: session
X-Telnyx-Session-ID: cf7861f2-635d-11f1-b7d5-02420aef201f
X-Telnyx-Leg-ID: cf7c762a-635d-11f1-b89b-02420aef201f
P-Asserted-Identity: "919113162180"<sip:919113162180@sip.telnyx.com;verstat=TN-Validation-Passed-C>
Content-Type: application/sdp
Content-Length: 555

v=0
o=Telnyx 1780916833 1780916834 IN IP4 50.114.150.28
s=Telnyx
c=IN IP4 50.114.150.28
t=0 0
m=audio 22166 RTP/AVP 18 8 0 9 102 101 103
a=rtpmap:18 G729/8000
a=fmtp:18 annexb=no
a=rtpmap:8 PCMA/8000
a=rtpmap:0 PCMU/8000
a=rtpmap:9 G722/8000
a=rtpmap:102 opus/48000/2
a=fmtp:102 useinbandfec=1; maxaveragebitrate=30000; maxplaybackrate=48000; ptime=20; minptime=10; maxptime=40
a=rtpmap:101 telephone-event/8000
a=fmtp:101 0-15
a=rtpmap:103 telephone-event/48000
a=fmtp:103 0-15
a=sendrecv
a=rtcp:22167 IN IP4 50.114.150.28
a=ptime:20
```

#### B2) 200 OK (FreeSWITCH -> Telnyx)
```text
SIP/2.0 200 OK
Via: SIP/2.0/TCP 64.16.250.10;branch=z9hG4bKebf3.7b2d4ead6e6cf75b42794ab0df27c685.0;rport=5060
Record-Route: <sip:64.16.250.10;transport=tcp;lr;r2=on;ftag=D3KcKcFt09Zac>
Record-Route: <sip:10.255.0.2;lr;r2=on;ftag=D3KcKcFt09Zac>
Record-Route: <sip:10.13.246.184:6050;lr;tnx=392.bd05>
From: "919113162180" <sip:919113162180@sip.telnyx.com>;tag=D3KcKcFt09Zac
To: <sip:12014269044@10.13.246.184>;tag=j5H26j6169m0e
Call-ID: 2176f6ce-9194-4479-885f-ad48f631188f
CSeq: 115915707 INVITE
Contact: <sip:12014269044@172.18.0.2:5060;transport=tcp>
User-Agent: FreeSWITCH-mod_sofia/1.10.12-release+git~20240802T210227Z~a88d069d6f~64bit
Accept: application/sdp
Allow: INVITE, ACK, BYE, CANCEL, OPTIONS, MESSAGE, INFO, UPDATE, REGISTER, REFER, NOTIFY
Supported: timer, path, replaces
Allow-Events: talk, hold, conference, refer
Content-Type: application/sdp
Content-Disposition: session
Content-Length: 248
P-Asserted-Identity: "12014269044" <sip:12014269044@10.13.246.184>

v=0
o=FreeSWITCH 1780922603 1780922604 IN IP4 172.18.0.2
s=FreeSWITCH
c=IN IP4 172.18.0.2
t=0 0
m=audio 16396 RTP/AVP 8 101
a=rtpmap:8 PCMA/8000
a=rtpmap:101 telephone-event/8000
a=fmtp:101 0-15
a=ptime:20
a=rtcp:16397 IN IP4 172.18.0.2
```

#### B3) BYE (FreeSWITCH -> Telnyx)
```text
BYE sip:mod_sofia@10.239.45.204:6000 SIP/2.0
Via: SIP/2.0/TCP 172.18.0.2;rport;branch=z9hG4bKjU7D5a99Sre6m
Route: <sip:64.16.250.10;transport=tcp;lr;r2=on;ftag=D3KcKcFt09Zac>
Route: <sip:10.255.0.2;lr;r2=on;ftag=D3KcKcFt09Zac>
Route: <sip:10.13.246.184:6050;lr;tnx=392.bd05>
Max-Forwards: 70
From: <sip:12014269044@10.13.246.184>;tag=j5H26j6169m0e
To: "919113162180" <sip:919113162180@sip.telnyx.com>;tag=D3KcKcFt09Zac
Call-ID: 2176f6ce-9194-4479-885f-ad48f631188f
CSeq: 115915716 BYE
User-Agent: FreeSWITCH-mod_sofia/1.10.12-release+git~20240802T210227Z~a88d069d6f~64bit
Allow: INVITE, ACK, BYE, CANCEL, OPTIONS, MESSAGE, INFO, UPDATE, REGISTER, REFER, NOTIFY
Supported: timer, path, replaces
Reason: Q.850;cause=16;text="NORMAL_CLEARING"
Content-Length: 0
```

### Mode C: No params (`ext-sip-ip`/`ext-rtp-ip` removed)

#### C0) REGISTER (FreeSWITCH -> Telnyx)
```text
REGISTER sip:sip.telnyx.com;transport=tcp SIP/2.0
Via: SIP/2.0/TCP 172.18.0.2;branch=z9hG4bKU1re95vg5QDUS
From: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=r9BXv9N3UFmZS
To: <sip:usermailakashkrsingh64252@sip.telnyx.com>
Call-ID: 66dff3c7-b7ef-42dc-a501-b432fa68de2f
CSeq: 115916667 REGISTER
Contact: <sip:gw+telnyx@172.18.0.2:5060;transport=tcp;gw=telnyx>
Expires: 3600
Authorization: Digest username="usermailakashkrsingh64252", realm="sip.telnyx.com", nonce="aicBoWonAHVsfsZcDq55yBT9jQtUxVeuXYxKuUA=", opaque="115916667/10.13.246.184", algorithm=MD5, uri="sip:sip.telnyx.com;transport=tcp", response="9f5c66465486428e2e92806026d1f84c"
Content-Length: 0
```

#### C0b) 200 OK to REGISTER (Telnyx -> FreeSWITCH)
```text
SIP/2.0 200 OK
Via: SIP/2.0/TCP 172.18.0.2;rport=31295;received=223.181.29.166;branch=z9hG4bKU1re95vg5QDUS
From: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=r9BXv9N3UFmZS
To: <sip:usermailakashkrsingh64252@sip.telnyx.com>;tag=eb51cce0171c859d37e55fe95de13457-6c370000
Call-ID: 66dff3c7-b7ef-42dc-a501-b432fa68de2f
CSeq: 115916667 REGISTER
Contact: <sip:gw+telnyx@172.18.0.2:5060;transport=tcp;alias=223.181.29.166~31295~2;gw=telnyx>;expires=3528
Server: Telnyx Registrar
Content-Length: 0
```

#### C1) INVITE (Telnyx -> FreeSWITCH)
```text
INVITE sip:12014269044@172.18.0.2:5060;transport=tcp;gw=telnyx SIP/2.0
Record-Route: <sip:64.16.250.10;transport=tcp;lr;r2=on;ftag=pFcB8X85g7Q2H>
Record-Route: <sip:10.255.0.2;lr;r2=on;ftag=pFcB8X85g7Q2H>
Record-Route: <sip:10.231.83.184:6050;lr;tnx=ee1.24c4>
Via: SIP/2.0/TCP 64.16.250.10;branch=z9hG4bK8543.725fdc15c593993de236795baf6b375c.0
Max-Forwards: 53
From: "919113162180" <sip:919113162180@sip.telnyx.com>;tag=pFcB8X85g7Q2H
To: <sip:12014269044@10.231.83.184>
Call-ID: 30d285e9-a4ec-4840-80f8-4ef0d6007f27
CSeq: 115914863 INVITE
Contact: <sip:mod_sofia@10.239.132.204:6000>
Allow: INVITE,ACK,BYE,CANCEL,OPTIONS,MESSAGE,INFO,UPDATE,REFER,NOTIFY
Supported: timer,path
u: talk,hold,conference,refer
Privacy: none
Content-Disposition: session
X-Telnyx-Session-ID: e1490d54-6359-11f1-b5b2-02420aef8e1f
X-Telnyx-Leg-ID: e14d4824-6359-11f1-bc57-02420aef8e1f
P-Asserted-Identity: "919113162180"<sip:919113162180@sip.telnyx.com;verstat=TN-Validation-Passed-C>
Content-Type: application/sdp
Content-Length: 555

v=0
o=Telnyx 1780913608 1780913609 IN IP4 50.114.146.17
s=Telnyx
c=IN IP4 50.114.146.17
t=0 0
m=audio 23702 RTP/AVP 0 8 9 18 102 101 103
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
a=rtcp:23703 IN IP4 50.114.146.17
a=ptime:20
```

#### C2) 200 OK (FreeSWITCH -> Telnyx)
```text
SIP/2.0 200 OK
Via: SIP/2.0/TCP 64.16.250.10;branch=z9hG4bK8543.725fdc15c593993de236795baf6b375c.0;rport=5060
Record-Route: <sip:64.16.250.10;transport=tcp;lr;r2=on;ftag=pFcB8X85g7Q2H>
Record-Route: <sip:10.255.0.2;lr;r2=on;ftag=pFcB8X85g7Q2H>
Record-Route: <sip:10.231.83.184:6050;lr;tnx=ee1.24c4>
From: "919113162180" <sip:919113162180@sip.telnyx.com>;tag=pFcB8X85g7Q2H
To: <sip:12014269044@10.231.83.184>;tag=HmFQ8rrr4vSem
Call-ID: 30d285e9-a4ec-4840-80f8-4ef0d6007f27
CSeq: 115914863 INVITE
Contact: <sip:12014269044@172.18.0.2:5060;transport=tcp>
User-Agent: FreeSWITCH-mod_sofia/1.10.12-release+git~20240802T210227Z~a88d069d6f~64bit
Accept: application/sdp
Allow: INVITE, ACK, BYE, CANCEL, OPTIONS, MESSAGE, INFO, UPDATE, REGISTER, REFER, NOTIFY
Supported: timer, path, replaces
Allow-Events: talk, hold, conference, refer
Content-Type: application/sdp
Content-Disposition: session
Content-Length: 248
P-Asserted-Identity: "12014269044" <sip:12014269044@10.231.83.184>

v=0
o=FreeSWITCH 1780920911 1780920912 IN IP4 172.18.0.2
s=FreeSWITCH
c=IN IP4 172.18.0.2
t=0 0
m=audio 16400 RTP/AVP 0 101
a=rtpmap:0 PCMU/8000
a=rtpmap:101 telephone-event/8000
a=fmtp:101 0-15
a=ptime:20
a=rtcp:16401 IN IP4 172.18.0.2
```

#### C3) BYE (FreeSWITCH -> Telnyx)
```text
BYE sip:mod_sofia@10.239.132.204:6000 SIP/2.0
Via: SIP/2.0/TCP 172.18.0.2;branch=z9hG4bKK6ySX68gm1rZr
Route: <sip:64.16.250.10;transport=tcp;lr;r2=on;ftag=pFcB8X85g7Q2H>
Route: <sip:10.255.0.2;lr;r2=on;ftag=pFcB8X85g7Q2H>
Route: <sip:10.231.83.184:6050;lr;tnx=ee1.24c4>
Max-Forwards: 70
From: <sip:12014269044@10.231.83.184>;tag=HmFQ8rrr4vSem
To: "919113162180" <sip:919113162180@sip.telnyx.com>;tag=pFcB8X85g7Q2H
Call-ID: 30d285e9-a4ec-4840-80f8-4ef0d6007f27
CSeq: 115914874 BYE
```

---

## Part 11: How to Inspect NAT Port Mappings Live

When STUN is active, FreeSWITCH opens outbound TCP connections to Telnyx. These connections pass through two NAT layers (Docker → Mac → Router → Internet). Here's how to inspect each layer.

### Step 1: See FreeSWITCH's port inside Docker container
```bash
docker exec minigenesys-freeswitch-mvp sh -c "
python3 -c \"
import socket, struct

def hex_to_ip(h): return socket.inet_ntoa(struct.pack('<I', int(h, 16)))
def hex_to_port(h): return int(h, 16)

with open('/proc/net/tcp') as f:
    lines = f.readlines()[1:]

for l in lines:
    parts = l.split()
    local = parts[1].split(':')
    rem = parts[2].split(':')
    state = parts[3]
    if state == '01':
        print(f'{hex_to_ip(local[0])}:{hex_to_port(local[1])}  ->  {hex_to_ip(rem[0])}:{hex_to_port(rem[1])}  ESTABLISHED')
\"
"
```
**Output:**
```
172.18.0.2:52767  ->  64.16.250.10:5060  ESTABLISHED
172.18.0.2:60379  ->  192.76.120.10:5060  ESTABLISHED
```
`52767` and `60379` are the ports FreeSWITCH opened inside Docker.

### Step 2: See Mac host port (Docker NAT translation)
```bash
netstat -an -p tcp | grep 64.16.250.10
netstat -an -p tcp | grep 192.76.120.10
```
**Output:**
```
tcp4  192.168.1.4.53864  ->  64.16.250.10.5060   ESTABLISHED
tcp4  192.168.1.4.54776  ->  192.76.120.10.5060  ESTABLISHED
```
Docker NAT translated container ports to Mac host ports:
```
172.18.0.2:52767  →  192.168.1.4:53864   (Docker NAT)
172.18.0.2:60379  →  192.168.1.4:54776   (Docker NAT)
```

### Step 3: See WAN port (Router NAT translation)
The router's NAT translation (`192.168.1.4:53864` → `223.181.24.11:<wan_port>`) is only visible from outside your network. The easiest way to read it is from Telnyx's `200 OK` response — the `rport=` value is exactly what Telnyx observed on the WAN side:
```bash
docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia global siptrace on"
docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia profile external siptrace on"
docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia profile external killgw telnyx"
docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia profile external startgw telnyx"
# wait ~10 seconds then
docker logs minigenesys-freeswitch-mvp --since 30s 2>&1 | grep "rport="
```
The `rport=<value>` in the `200 OK` Via is the final WAN port after all NAT translations.

### Full NAT chain summary
```
FreeSWITCH (Docker)       Mac host                  WAN (internet)
172.18.0.2:52767    →   192.168.1.4:53864    →   223.181.24.11:<rport>   →   64.16.250.10:5060 (Telnyx)
     Docker NAT ↑              Router NAT ↑
  (visible from /proc/net/tcp)   (visible from netstat on Mac)   (visible from rport in 200 OK)
```

### Step 4: Check STUN-resolved external IP
```bash
docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia status profile external" | grep -E "Ext-SIP-IP|Ext-RTP-IP|SIP-IP|RTP-IP"
```
**Output (with STUN active):**
```
RTP-IP      172.18.0.2
Ext-RTP-IP  stun:stun.l.google.com:19302
SIP-IP      172.18.0.2
Ext-SIP-IP  223.181.24.11
```

---

### REGISTER comparison takeaway
- STUN mode changes advertised signaling identity to public (`223.181.29.166:16776`) in both `Via` and `Contact`.
- `auto` and no-params both advertise Docker IP (`172.18.0.2`) in REGISTER and receive `received=223.181.29.166` in response.
- Telnyx still registers successfully in all three because it records source tuple via `rport/received` and `alias=public_ip~port`.
