# Twilio Integration Technical Documentation

## 1. Architectural Overview: TwiML vs. TwiML App

It is critical to distinguish between these two concepts in the Mini Genesys architecture:

*   **TwiML (Twilio Markup Language)**:
    *   **Definition**: An XML-based set of instructions used to tell Twilio how to handle a call.
    *   **Mechanism**: Our backend returns TwiML strings in response to Twilio's HTTP webhooks.
    *   **Execution**: Twilio executes tags sequentially (e.g., `<Say>` -> `<Pause>` -> `<Redirect>`).
    *   **Bridging**: The final connection is achieved via the `<Dial><Client>agentId</Client></Dial>` instruction.

*   **TwiML App**:
    *   **Definition**: A configuration object in the Twilio Console.
    *   **Primary Roles**: 
        1. Used for generating **Access Tokens** for the browser (Voice SDK).
        2. Required for **Outbound calls** initiated from the browser.
    *   **Note**: For **Inbound** calls, the TwiML App webhook is NOT used for routing or bridging. Inbound flow is controlled entirely by the Phone Number webhook.

---

## 2. Inbound Call Flow Lifecycle

Mini Genesys uses a **Polling & Bridge** pattern to manage real-time agent assignment via Kafka.

1.  **Call Entry**: Caller dials the Twilio number -> Twilio calls `POST /twilio/inbound`.
2.  **Initial Greeting**: Backend returns TwiML with a greeting and a `<Redirect>` to the `/bridge` endpoint.
3.  **Wait-Loop (Polling)**: Twilio calls `GET /twilio/bridge`.
    *   **Case A (No Agent Assigned)**: Backend returns TwiML with a "queue" message and a `<Redirect>` back to itself (polling).
    *   **Case B (Agent Assigned)**: Once the `assignedAgentId` is updated in the database (via Kafka), the backend returns `<Dial><Client>agentId</Client></Dial>`.
4.  **Connection**: Twilio connects the PSTN caller to the WebRTC browser client.

---

## 3. API Documentation

### POST /api/v1/telephony/twilio/inbound
*   **Purpose**: First point of contact for an incoming call.
*   **Request Params**: `CallSid`, `From`, `To`.
*   **Response**: TwiML (Greeting + Redirect to `/bridge`).

### GET /api/v1/telephony/twilio/bridge
*   **Purpose**: Polling endpoint to check for agent assignment.
*   **Request Params**: `callSid`.
*   **Response**: 
    *   *Queued*: TwiML (Wait message + Redirect to self).
    *   *Assigned*: TwiML (`<Dial><Client>`).

---

## 4. Sequence Diagram

```text
CALLER          TWILIO CLOUD          BACKEND (8092)          ROUTING SERVICE
  |                  |                      |                        |
  |---(1) Dial No.-->|                      |                        |
  |                  |---(2) POST /inbound->|                        |
  |                  |                      |---(3) Publish Call---->|
  |                  |<---(4) XML: Redirect-|                        |
  |                  |                      |                        |
  |                  |---(5) GET /bridge--->|                        |
  | <--(6) "Wait" ---|                      |---(7) Agent null? -----|
  |                  |<---(8) XML: Redirect-|                        |
  |                  |          ...         |                        |
  |                  |          ...         |---(9) Kafka ASSIGN! ---|
  |                  |                      |                        |
  |                  |---(10) GET /bridge-->|                        |
  |                  |                      |---(11) Agent ID found! |
  |                  |<---(12) <Dial><Client|                        |
  |                  |                      |                        |
  |<====(13) BRIDGED AUDIO (PSTN <-> WebRTC)========================>| BROWSER AGENT
```
