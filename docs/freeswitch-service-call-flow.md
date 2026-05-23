# FreeSWITCH Service — Inbound Call Flow

> This document traces an inbound PSTN call step by step through FreeSWITCH and our Java `freeswitch-service`.
> Each step includes the exact class, method, FreeSWITCH commands/events, and data involved.
> Steps are added incrementally as they are understood and discussed.

---

## Step 1 — Inbound Call Arrives at FreeSWITCH

**What happens here:** Entirely inside FreeSWITCH. Our Java code has not run yet.

### How the carrier knows where to send the call

When using **Twilio Elastic SIP Trunking** (or any SIP carrier), we register our FreeSWITCH server's public IP and port inside the carrier's dashboard:

```
Twilio Console
  → Elastic SIP Trunking
    → Trunks → [Your Trunk]
      → Termination → Origination URIs
        → sip:103.45.67.89:5060   ← our FreeSWITCH public IP
```

When a customer dials our DID number, the carrier sends a SIP `INVITE` packet directly to that IP on port **5060**.

> **Note:** This is separate from Twilio's webhook/TwiML API. In SIP Trunking mode, Twilio acts purely as a SIP carrier — it doesn't call our Java API. The Java Twilio path (`/api/v1/telephony/twilio/inbound`) is a completely independent, parallel call path.

---

### What FreeSWITCH does when the INVITE arrives

The `external` sofia profile (configured in `sofia.conf.xml`) receives the SIP `INVITE` on port 5060 and routes it into the `public` dialplan context.

**File:** [`docker/freeswitch/conf/autoload_configs/sofia.conf.xml`](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/autoload_configs/sofia.conf.xml)
```xml
<profile name="external">
  <param name="sip-ip"     value="0.0.0.0"/>  <!-- bind on all local interfaces -->
  <param name="ext-sip-ip" value="auto"/>      <!-- advertise public IP in SIP headers -->
  <param name="sip-port"   value="5060"/>
  <param name="context"    value="public"/>    <!-- route all inbound calls here -->
</profile>
```

> **`sip-ip` vs `ext-sip-ip`:** FreeSWITCH runs inside Docker. The OS inside the container only knows about the container's local IP (e.g. `172.x.x.x`), not the public internet IP. `sip-ip=0.0.0.0` tells the OS to open a socket and listen on ALL local interfaces. `ext-sip-ip=auto` tells FreeSWITCH to auto-detect its public IP and stamp that into the SIP/SDP headers it sends back to the carrier — because the carrier must know the public IP to route reply packets back.

The `public` context matches the `inbound_pstn` extension in the dialplan:

**File:** [`docker/freeswitch/conf/dialplan/public.xml`](file:///Users/akash.singh/Desktop/MiniGenesys/docker/freeswitch/conf/dialplan/public.xml)
```xml
<extension name="inbound_pstn">
  <condition field="destination_number" expression="^(.*)$">
    <action application="answer"/>
    <action application="playback" data="tone_stream://%(1000,0,800)"/>
    <action application="park"/>
  </condition>
</extension>
```

FreeSWITCH executes three actions in sequence:

| Action | What it does |
|---|---|
| `answer` | Accepts the call — establishes the RTP audio channel so media can flow |
| `playback tone_stream://%(1000,0,800)` | Plays a 1-second 800Hz ringback tone so the caller hears audio instead of dead silence |
| `park` | Puts the call into a held/waiting state inside FreeSWITCH |

When `park` executes, FreeSWITCH fires a **`CHANNEL_PARK`** ESL event containing the call's metadata (UUID, caller number, direction, etc.). This is the event our Java service reacts to.

---

## Step 2 — FreeSWITCH ESL Event Arrives at Our Java Service

### 2a — How the ESL Connection is Established (Startup)

**Class:** [`FreeswitchEslService.java`](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/service/FreeswitchEslService.java)

When the Spring application starts, `@PostConstruct` triggers `start()` → `connectWithRetry()`:

```java
// L68-L72
@PostConstruct
public void start() {
    running.set(true);
    scheduler.schedule(this::connectWithRetry, 0, TimeUnit.SECONDS);
}
```

Inside `connectWithRetry()`, four things happen in order:

```java
// L81 — Create an empty ESL client object. No connection yet.
Client newClient = new Client();

// L82-L92 — Register our callback BEFORE connecting (so no events are missed).
newClient.addEventListener(new IEslEventListener() {
    public void eventReceived(EslEvent event) {
        handleEvent(event);  // ← every FreeSWITCH event lands here
    }
    public void backgroundJobResultReceived(EslEvent event) {
        log.debug("Background job result: eventName={}", event.getEventName());
    }
});

// L94 — Open the actual TCP connection and authenticate.
newClient.connect(eslHost, eslPort, eslPassword, connectTimeoutSeconds);
// internally: TCP connect → FreeSWITCH sends "auth/request" → library sends "auth ClueCon" → "+OK accepted"

// L95 — Tell FreeSWITCH which event types to push down this connection.
newClient.setEventSubscriptions("plain", EVENT_SUBSCRIPTIONS);
// sends: "event plain CHANNEL_CREATE CHANNEL_ANSWER CHANNEL_PARK CHANNEL_BRIDGE CHANNEL_HANGUP_COMPLETE"
```

**`EVENT_SUBSCRIPTIONS` constant (L32-L33):**
```java
private static final String EVENT_SUBSCRIPTIONS =
    "CHANNEL_CREATE CHANNEL_ANSWER CHANNEL_PARK CHANNEL_BRIDGE CHANNEL_HANGUP_COMPLETE";
```

Without `setEventSubscriptions()`, FreeSWITCH would push **no events at all** even though the TCP connection is open.

The `"plain"` format means events arrive as plain-text key=value headers (not JSON/XML). The library parses them into an `EslEvent` object before calling our `eventReceived()`.

> **Retry logic:** If `connect()` fails (e.g. FreeSWITCH Docker container isn't ready yet), the catch block schedules another attempt after 15 seconds (`retryIntervalSeconds`). This handles the race condition where our Java service starts before FreeSWITCH is fully up.

The result is a **persistent TCP connection** (port **8022**) that stays open indefinitely. FreeSWITCH pushes events proactively — our code never polls.

---

### 2b — Event Dispatch: handleEvent()

**Method:** [`handleEvent(EslEvent event)` — L139](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/service/FreeswitchEslService.java#L139-L166)

Every event from all 5 subscriptions lands here first. The method extracts common headers and routes to the right handler:

```java
String eventName  = event.getEventName();
Map<String, String> headers = event.getEventHeaders();

String uuid        = headers.get("Unique-ID");               // FreeSWITCH channel UUID
String caller      = headers.get("Caller-Caller-ID-Number"); // e.g. "+919876543210"
String destination = headers.get("Caller-Destination-Number"); // the DID dialed
String callState   = headers.get("Channel-Call-State");

if ("CHANNEL_PARK".equals(eventName)) {
    handleChannelPark(uuid, headers, caller);
} else if ("CHANNEL_ANSWER".equals(eventName)) {
    handleChannelAnswer(uuid, headers);
} else if ("CHANNEL_HANGUP_COMPLETE".equals(eventName)) {
    handleChannelHangupComplete(uuid);
}
```

For our inbound flow, `CHANNEL_PARK` fires when Step 1's `park` action executes. The `uuid` here is the FreeSWITCH channel UUID for the **customer's call leg** — this UUID is used as the primary key throughout all subsequent steps.

---

## Step 3 — handleChannelPark: Session Created + call-service Called

**Method:** [`handleChannelPark(String uuid, Map<String, String> headers, String caller)`](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/service/FreeswitchEslService.java#L168-L197)

When FreeSWITCH parks the call, `CHANNEL_PARK` fires. Our Java service picks it up and does the following:

**3a. Idempotency Check & Direction Filter:**
```java
String direction = headers.get("Call-Direction");
if ("inbound".equalsIgnoreCase(direction)) {
    if (repository.existsById(uuid)) {
        return; // already processed, bail out
    }
    // ...
}
```
This ensures we only process inbound parks (outbound calls can also be parked) and prevents duplicate processing if FreeSWITCH re-fires the event.

**3b. Ask `call-service` to create an internal Call record:**
```java
String internalCallId = callServiceClient.createInternalCall(tenantId, caller);
```
[`CallServiceClient.createInternalCall()`](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/client/CallServiceClient.java#L25-L44) makes an HTTP POST to `http://call-service/api/v1/calls`. The `call-service` creates a `Call` record in the DB, matches it to a campaign/queue, and publishes a routing request to Kafka. It returns its own UUID (`internalCallId`).

**3c. Save the FreeSWITCH Call Session:**
```java
FreeswitchCallSession session = FreeswitchCallSession.builder()
        .customerUuid(uuid)           // "abc-123-uuid" (FreeSWITCH channel UUID)
        .internalCallId(internalCallId) // "call-uuid-789" (call-service ID)
        .tenantId(tenantId)
        .callerId(caller)
        .status("PARKED")
        .build();
repository.save(session);
```
**Status at this point:** `PARKED`. The customer is still waiting in FreeSWITCH hearing hold music (or silence, depending on the dialplan).

---

## Step 4 & 5 — Routing Service (Kafka)

This happens *asynchronously* outside of `freeswitch-service`:
- `call-service` → Kafka (`routing-requests`)
- `routing-service` runs its Lua script to find an available agent in Redis
- `routing-service` → Kafka (`routing-events`) with payload:
  ```json
  {
    "callId": "call-uuid-789",
    "agentId": "AG-FREESWITCH",
    "status": "ASSIGNED",
    "telephonyProvider": "FREESWITCH"
  }
  ```

---

## Step 6 — freeswitch-service Receives RoutingEvent

**Class:** [`RoutingEventConsumer.java`](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/kafka/RoutingEventConsumer.java)  
**Method:** [`FreeswitchCallService.handleAssignment(RoutingEvent event)`](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/service/FreeswitchCallService.java#L23-L67)

When the Kafka event arrives, `handleAssignment` takes over:

```java
// 1. Skip if not meant for FreeSWITCH
if (!"FREESWITCH".equals(event.getTelephonyProvider()) || !"ASSIGNED".equals(event.getStatus())) return;

// 2. Find the session using the internalCallId from Kafka
FreeswitchCallSession session = repository.findByInternalCallId(event.getCallId()).get();

// 3. Pre-generate a UUID for the agent's upcoming channel
String agentUuid = UUID.randomUUID().toString(); // e.g. "def-456-uuid"

// 4. Update session
session.setAssignedAgentId(event.getAgentId());
session.setAgentUuid(agentUuid);
session.setStatus("DIALING_AGENT");
repository.save(session);
```
**Status at this point:** `DIALING_AGENT`.

---

## Step 7 — The Bridge Commands (uuid_transfer + originate)

In the same `handleAssignment` method, we fire two asynchronous commands to FreeSWITCH almost instantly:

**Command 1 — Move the customer into a conference room:**
```java
eslService.transferCustomerToConference(session.getCustomerUuid());
// Under the hood: c.sendAsyncApiCommand("uuid_transfer", "abc-123-uuid conference:abc-123-uuid@default inline");
```
FreeSWITCH instantly creates a conference room named `abc-123-uuid@default` and moves the customer into it.

**Command 2 — Dial the agent and drop them into the same room:**
```java
eslService.originateCallToAgent(event.getAgentId(), agentUuid, session.getCustomerUuid(), session.getCallerId());
// Under the hood: c.sendAsyncApiCommand("originate", "{origination_uuid=def-456-uuid...}sofia/internal/sip:agent@localhost &conference(abc-123-uuid@default)");
```
FreeSWITCH initiates an outbound call to the agent's SIP client. The `&conference` argument tells FreeSWITCH to drop the agent into the `abc-123-uuid@default` room the exact millisecond they answer.

---

## Step 8 — Agent Answers (CHANNEL_ANSWER)

When the agent clicks "Answer" in their WebRTC client, FreeSWITCH connects them into the conference room and fires the `CHANNEL_ANSWER` event for the agent's channel.

**Method:** [`handleChannelAnswer(String uuid, Map<String, String> headers)`](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/service/FreeswitchEslService.java#L199-L221)

```java
String direction = headers.get("Call-Direction");
if ("outbound".equalsIgnoreCase(direction)) { // we originated the agent, so it's outbound
    Optional<FreeswitchCallSession> sessionOpt = repository.findByAgentUuid(uuid);
    if (sessionOpt.isPresent()) {
        FreeswitchCallSession session = sessionOpt.get();
        if ("DIALING_AGENT".equals(session.getStatus())) {
            
            // Mark session as BRIDGED
            session.setStatus("BRIDGED");
            repository.save(session);

            // Notify call-service that the call has started
            callServiceClient.startCall(session.getTenantId(), session.getInternalCallId());
        }
    }
}
```
**Status at this point:** `BRIDGED`. Both the customer and the agent are inside the conference room, and audio is flowing. `call-service` has marked the call as `IN_PROGRESS` and started the billing/duration timer.

---

## Step 9 — Hangup / Cleanup (CHANNEL_HANGUP_COMPLETE)

Either party (customer or agent) hangs up. FreeSWITCH fires `CHANNEL_HANGUP_COMPLETE` with the UUID of the person who hung up.

**Method:** [`handleChannelHangupComplete(String uuid)`](file:///Users/akash.singh/Desktop/MiniGenesys/backend/freeswitch-service/src/main/java/com/minigenesys/freeswitch/service/FreeswitchEslService.java#L223-L260)

```java
// Check who hung up
Optional<FreeswitchCallSession> sessionOpt = repository.findById(uuid);
boolean isCustomer = sessionOpt.isPresent();
if (!isCustomer) {
    sessionOpt = repository.findByAgentUuid(uuid);
}

FreeswitchCallSession session = sessionOpt.get();

// Force the OTHER party to hang up too
if (isCustomer && session.getAgentUuid() != null) {
    c.sendAsyncApiCommand("uuid_kill", session.getAgentUuid()); // kill agent leg
} else if (!isCustomer && session.getCustomerUuid() != null) {
    c.sendAsyncApiCommand("uuid_kill", session.getCustomerUuid()); // kill customer leg
}

// Mark session as COMPLETED
session.setStatus("COMPLETED");
repository.save(session);

// Notify call-service
callServiceClient.completeCall(session.getTenantId(), session.getInternalCallId());
```
`call-service` marks the call as `COMPLETED`, stops the billing timer, and fires any post-call events.

The conference room becomes empty, and FreeSWITCH automatically destroys it. The call flow is complete.
