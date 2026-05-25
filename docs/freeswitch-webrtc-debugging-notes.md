# FreeSWITCH WebRTC Debugging Notes

## 1. Problem Timeline

- **Symptom 1: Inbound calls stop reaching the browser / No popup**
  - *Context:* The system was supposed to route PSTN calls from Telnyx to the browser agent over WebRTC via FreeSWITCH. The call connected to FreeSWITCH, but the agent's browser never rang.
  - *Symptom:* The browser popup was not appearing. The caller heard ringing but no connection occurred.
  - *Fix Applied:* We investigated the `routing-events` Kafka topic. `routing-service` was sending events with a null `telephonyProvider`, which `freeswitch-service` silently ignored. We updated the backend to properly propagate `"telephonyProvider": "FREESWITCH"`.
  - *Result:* `freeswitch-service` started processing the routing event, but the popup still didn't appear.

- **Symptom 2: Originate command executed but call fails instantly (No popup)**
  - *Context:* `freeswitch-service` began attempting to bridge the call to the agent via `originate`.
  - *Symptom:* The FreeSWITCH console showed immediate `USER_NOT_REGISTERED` or `NO_ROUTE_DESTINATION`. The dial string `sofia/internal/sip:agentId@localhost` was failing because we didn't have static XML directory users for agents.
  - *Fix Applied:* For "blind registrations" (endpoints that register via WSS without an entry in `directory/default.xml`), FreeSWITCH requires the syntax `agentId%localhost`. We modified the `originate` dial string in `FreeswitchEslService` to use `%localhost`.
  - *Result:* The browser popup successfully appeared when a call came in!

- **Symptom 3: Popup appeared but call disconnected immediately after answering**
  - *Context:* The browser agent clicked "Answer" on the incoming call popup.
  - *Symptom:* The browser sent a `200 OK`, FreeSWITCH activated DTLS and ICE, but exactly 8 seconds later, the browser sent a `BYE`, causing the call to drop. 
  - *Investigation:* We enabled SIP tracing and found that FreeSWITCH was advertising its internal Docker bridge IP (`172.18.0.2`) in the SDP offer. The browser running on the Mac host could not route to this private IP, causing ICE to time out and the browser to abort the call.
  - *Fix Applied:* We defined `external_rtp_ip` in `vars.xml` to inject the host's LAN IP (`192.168.1.4`) into the SDP via `ndlb-force-ctx-ip` in `sofia.conf.xml`.
  - *Result:* The call finally worked. Media (ICE and DTLS) successfully reached a `READY` state, and the call remained bridged until the user manually hung up.

---

## 2. Commands Used During Debugging

### Docker & FreeSWITCH CLI Commands
- `docker logs minigenesys-freeswitch-mvp --since 5m 2>&1`
  - *Checks:* Recent FreeSWITCH console logs.
  - *Why run:* To see if FreeSWITCH is receiving the INVITE and originating the outbound leg to the agent.
  - *Expected:* Logs showing `sofia/internal...` channel creation.
  - *Found:* Initially, missing originate commands. Later, ICE and DTLS handshake logs.

- `docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia status"`
  - *Checks:* Status of the SIP profiles (internal/external) and WSS bindings.
  - *Why run:* To verify that `internal` was bound to `wss://0.0.0.0:7443`.
  - *Expected:* `internal` profile showing `WS-BIND-URI` for `7443`.
  - *Found:* Confirmed the WSS transport was active and listening.

- `docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia status profile internal reg"`
  - *Checks:* Currently registered SIP/WebRTC endpoints.
  - *Why run:* To see if the browser successfully registered.
  - *Found:* Confirmed the browser `akash-freeswitch` was registered via WSS.

- `docker exec minigenesys-freeswitch-mvp fs_cli -x "reloadxml"` and `fs_cli -x "sofia profile internal restart"`
  - *Why run:* To apply changes made to `vars.xml` and `sofia.conf.xml` without restarting the whole container.

- `docker exec minigenesys-freeswitch-mvp fs_cli -x "sofia profile internal siptrace on"` and `fs_cli -x "console loglevel debug"`
  - *Checks:* Full SIP messaging (INVITE, SDP, 200 OK, BYE) and detailed media logs.
  - *Why run:* Because the call was hanging up after answer and we needed to see the SDP payloads and exact hangup causes.
  - *Found:* Revealed the `172.18.0.2` Docker IP in the FreeSWITCH SDP offer, pointing exactly to the NAT/ICE root cause.

### Backend & Service Commands
- `pkill -f freeswitch-service && ./gradlew :freeswitch-service:bootRun &`
  - *Why run:* To rapidly compile and restart the Java service after modifying the dial string or DTOs.

- `docker exec beautiful_ride psql -U postgres -d minigenesys_call_service -c "UPDATE agents SET status='AVAILABLE', active_call_id=NULL WHERE agent_id='akash-freeswitch';"`
  - *Checks/Modifies:* Clears stuck agent states in the Postgres DB.
  - *Why run:* When calls failed halfway, the DB thought the agent was still busy. This reset the agent so they could receive the next test call.

- `docker exec beautiful_ride redis-cli keys "tenant:tenant-freeswitch:call:*"` and `redis-cli del ...`
  - *Checks/Modifies:* Clears stuck calls in Redis.
  - *Why run:* Prevented routing-service from thinking previous test calls were still active in the queue.

---

## 3. Evidence Collected

- **Telnyx INVITE reaching FreeSWITCH:** Logs showed `New Channel sofia/external/... receiving invite from 192.76.120.10:5060`. Proven the inbound PSTN leg was healthy.
- **inbound_pstn dialplan executing:** `Processing ... in context public` followed by `answer()` and `park()`.
- **CHANNEL_PARK received:** `freeswitch-service` logged `[ESL-EVENT] name=CHANNEL_PARK uuid=...` confirming the ESL socket received the park event.
- **routing event missing provider:** `freeswitch-service` logs were quiet. Checking `routing-service` logs revealed `{"telephonyProvider": null}` in the Kafka JSON payload. This proved why `freeswitch-service` skipped the event (it only processes `FREESWITCH`).
- **originate command missing:** Because of the null provider, `c.sendAsyncApiCommand("originate", ...)` was never called.
- **%localhost fix:** After updating to `sofia/internal/akash-freeswitch%localhost`, `freeswitch-service` logged `Originated call to agent...` and FreeSWITCH logged `New Channel sofia/internal/akash-freeswitch`. The popup appeared.
- **ICE/DTLS logs:** `Activating Audio ICE`, `Changing audio DTLS state from OFF to HANDSHAKE`.
- **Browser BYE caused by ICE failure:** Trace showed `BYE` arriving from the WSS socket exactly 8 seconds after DTLS started. This proved the browser aborted the call, not FreeSWITCH.
- **NORMAL_CLEARING:** Once the browser hung up, `freeswitch-service` sent the hangup command to the PSTN leg, resulting in a normal cleanup.

---

## 4. Root Causes Found

1. **routing-events missing `telephonyProvider`**
   - *Why it broke:* `freeswitch-service` relies on `telephonyProvider == "FREESWITCH"` to differentiate calls from Twilio. Because `RoutingEngine` didn't copy this field into `AssignmentResult`, the Kafka event went out with `null`, and `freeswitch-service` ignored the call.

2. **Wrong dial string for WSS registrations**
   - *Why it broke:* We tried `sip:agentId@localhost`. FreeSWITCH expects explicit directory XML entries for `@domain` routing. 
   - *Fix:* For dynamic WebRTC clients ("blind registrations"), we must use `agentId%localhost` which tells FreeSWITCH to look up the active registration in memory without validating a directory user.

3. **FreeSWITCH Docker / NAT WebRTC Issue**
   - *Why it broke:* FreeSWITCH lives inside a Docker bridge (`172.18.0.x`). When negotiating WebRTC, it offered `172.18.0.2` as its ICE candidate. The browser (on the host OS) cannot route UDP to the Docker bridge subnet, causing ICE to fail and the browser to abort the call after 8 seconds.
   - *Fix:* We set `external_rtp_ip` to the Host LAN IP (`192.168.1.4`) and forced FreeSWITCH to use it via `ndlb-force-ctx-ip=true`.

4. **Missing Opus Codec**
   - WebRTC strongly prefers/requires Opus. We ensured `mod_opus` was loaded in `modules.conf.xml`.

---

## 5. Failed or Temporary Fixes

- **Frontend SDP Rewriting:** Initially, we tried adding a `session.on('sdp')` listener in `freeswitch-webrtc.service.ts` to manually string-replace `172.18.0.2` with `127.0.0.1`. This was a hacky workaround and was cleanly reverted once we configured FreeSWITCH to advertise the correct IP natively.
- **Repeated `rtcp_mux` experiments:** We added both `rtcp_mux` and `rtcp-mux` to the `originate` string. The hyphenated version was redundant and removed to keep the code clean.
- **Local-network-acl experiments:** We considered complex ACL rules in `acl.conf.xml` but ultimately relied on the simpler `ext-rtp-ip` config.
- **Broad random debugging:** We initially grepped Java code looking for "why is the popup not showing" before realizing we just needed to look at the exact Kafka payload in the logs to see the missing provider.

---

## 6. Final Working Changes

- **routing-service telephonyProvider propagation:** Updated `CallRequest`, `AssignmentResult`, `RoutingEngine`, and `RetryProcessor` to copy the provider tag perfectly.
- **FreeswitchEslService `%localhost` dial string:** Using `sofia/internal/agentId%localhost` for blind WebRTC origination.
- **WebRTC originate variables:** Appended `{media_webrtc=true,rtp_secure_media=true,rtcp_mux=true}` to the `originate` command to ensure the outbound leg negotiated DTLS and ICE.
- **`mod_opus`:** Added to `modules.conf.xml`.
- **`freeswitch.xml` includes `vars.xml`:** Ensures global preprocessor variables load.
- **`vars.xml`:** Defines `external_rtp_ip=192.168.1.4` (local dev NAT workaround).
- **`sofia.conf.xml`:** Configured WSS bindings, WebRTC flags, and `ndlb-force-ctx-ip="true"`.
- **`docker-compose.yml`:** Mapped strict RTP ports (`16384-16400`) and the WSS port (`7443`).

---

## 7. Final Working Flow

1. **Inbound PSTN:** `Telnyx` sends INVITE -> `FreeSWITCH` external profile.
2. **Dialplan:** `public` dialplan triggers `answer` and `park`.
3. **Event Notification:** FreeSWITCH emits `CHANNEL_PARK` over the ESL socket.
4. **Java Backend:** `freeswitch-service` detects the park, calls API gateway -> `call-service` -> `routing-service`.
5. **Routing:** `routing-service` finds `akash-freeswitch` and publishes `routing-events` with `telephonyProvider: "FREESWITCH"`.
6. **Origination:** `freeswitch-service` consumes event, sends `originate ... sofia/internal/akash-freeswitch%localhost &conference(...)`.
7. **Browser Ringing:** FreeSWITCH sends INVITE over WSS to browser. Popup appears.
8. **Media Negotiation:** Browser answers. SDP exchange occurs. FreeSWITCH uses `external_rtp_ip`. ICE and DTLS reach `READY` state.
9. **Bridged:** The browser leg and Telnyx leg are merged in a conference bridge. Two-way audio is established.
10. **Hangup:** User clicks end call -> Browser sends `BYE` -> FreeSWITCH tears down agent leg -> `freeswitch-service` actively terminates Telnyx leg.

---

## 8. Lessons Learned

- **SIP Signaling vs RTP Media:** Just because a call rings (Signaling/WSS) doesn't mean you can hear it (Media/RTP). Signaling uses TCP/WSS on port 7443. Media uses UDP on ports 16384+. If the call drops exactly 8-10 seconds after answering, it is almost always a Media/ICE/NAT failure.
- **ESL Control vs Sofia SIP engine:** The Java app (ESL) acts as a remote control. It doesn't handle audio. It just tells the `sofia` SIP engine what to do. 
- **Blind WebRTC Registrations (`%localhost`):** Standard SIP phones have passwords and directory XML entries. Browsers often use "blind registrations" just by opening a WebSocket. To dial them, you must use `%localhost` instead of `@localhost`.
- **Docker NAT & ICE:** FreeSWITCH inside Docker doesn't know its public/host IP. It advertises its internal Docker IP to the browser. The browser cannot route to Docker's internal subnet from the host OS, causing media to blackhole. You *must* use `ext-rtp-ip` and port mapping to explicitly bridge the NAT gap.
- **Read Logs by Pipeline Stage:** Don't guess. Follow the trace sequentially: Did Telnyx reach FS? Did FS park? Did ESL receive it? Did Kafka route it? Did FS originate? Did the browser answer? Did DTLS succeed? Stop and investigate exactly at the step where the chain breaks.
