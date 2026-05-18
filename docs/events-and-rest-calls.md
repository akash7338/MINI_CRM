# MiniGenesys System Events & Inter-Service API Calls

This document outlines the distributed communication patterns (both asynchronous Kafka events and synchronous REST calls) that wire the MiniGenesys microservices together.

---

## 1. Asynchronous Communication (Kafka Events)

### Topic: `agent-events`
* **Producer:** `AgentStateService`
* **When Called:** Triggered any time an agent's state transitions. This includes explicit actions (logging in, manually changing status to `AVAILABLE` or `BUSY`) and implicit actions (the `detectDisconnects()` scheduler realizing an agent's heartbeat timed out, marking them `OFFLINE`).
* **Purpose:** Broadcasts the source-of-truth agent status to the entire platform.
* **Key Consumers:**
  * **`CallService`:** Listens specifically for `AGENT_DISCONNECTED` events. If an agent goes offline mid-call, it intercepts this to requeue or terminate any orphaned calls.
  * **`WebsocketGateway`:** Consumes the event and pushes the real-time status update to the frontend UI so supervisors and agents see the live status change.
  * **`AuditService` & `AnalyticsService`:** Consumes for historical logging and real-time dashboard metrics.

### Topic: `call-events`
* **Producer:** `CallService`
* **When Called:** Triggered when a new call is created, started, or ended within the core system.
* **Purpose:** Signals the beginning or status change of a call lifecycle that requires active routing or tracking.
* **Key Consumers:**
  * **`RoutingService`:** Reacts to the "call created" event by executing its queueing and skill-matching logic to find an available agent.
  * **`WebsocketGateway`:** Pushes the call entity update to the UI.

### Topic: `routing-events`
* **Producer:** `RoutingService`
* **When Called:** Published immediately after the routing engine attempts to assign an agent to a waiting call (whether it succeeds with `ASSIGNED` or fails).
* **Purpose:** Synchronizes all dependent services with the outcome of the routing decision.
* **Key Consumers:**
  * **`CallService`:** Updates the main call record in PostgreSQL to reflect the newly assigned agent.
  * **`AgentStateService`:** Automatically switches the assigned agent's status from `AVAILABLE` to `BUSY` and sets their `activeCallId` so they cannot receive another simultaneous call.
  * **`TelephonyService`:** Links the Twilio call session with the assigned agent's SIP/Client ID, readying the system to bridge the VoIP audio.
  * **`WebsocketGateway`:** Notifies the browser so the agent sees the incoming call pop-up.

### Topic: `call-lifecycle-events`
* **Producer:** `CallService`
* **When Called:** Triggered when a call reaches a terminal state (completed, failed, canceled).
* **Purpose:** Acts as the teardown signal to initiate cleanup procedures across all microservices.
* **Key Consumers:**
  * **`AgentStateService`:** Hears the call completed event, clears the `activeCallId` from the agent, and flips their state from `BUSY` back to `AVAILABLE` so they can receive the next call.
  * **`WebsocketGateway`:** Instructs the UI to close the active call view.

### Topic: `telephony-events`
* **Producer:** `TelephonyService`
* **When Called:** Every time the Twilio webhook sends a physical network status update (e.g., ringing, in-progress, completed).
* **Purpose:** Provides a raw, low-level feed of the physical telephony network state to the rest of the platform.

---

## 2. Synchronous Communication (Cross-Service REST Calls)

### `POST /api/v1/agents/internal`
* **Caller:** `UserService`
* **Callee:** `AgentStateService`
* **When Called:** When a new Agent User account is created by an admin via the User Service.
* **Purpose:** Keeps the system synchronized by instantly creating a matching Agent profile (with skills and routing properties) in the `AgentStateService` database alongside the Auth credentials.

### `POST /api/v1/calls`
* **Caller:** `TelephonyService`
* **Callee:** `CallService`
* **When Called:** When an inbound phone call hits the Twilio webhook, and it's recognized as a brand new call SID.
* **Purpose:** Instructs the Call Service to generate an internal system `callId` and kick off the standard `call-events` lifecycle and routing process.

### `POST /api/v1/calls/{callId}/start`
* **Caller:** `TelephonyService`
* **Callee:** `CallService`
* **When Called:** When Twilio sends a status callback stating the call is now "in-progress" (meaning the agent physically answered the browser softphone).
* **Purpose:** Updates the core system state that the call has moved from ringing to actively connected, starting the live call timer.

### `POST /api/v1/calls/{callId}/complete`
* **Caller:** `TelephonyService`
* **Callee:** `CallService`
* **When Called:** When Twilio sends a status callback indicating the call has hung up (e.g., "completed", "failed", "canceled").
* **Purpose:** Formally terminates the internal call. This REST call forces the Call Service to wrap up the call and publish the `call-lifecycle-events` which eventually frees up the agent.
