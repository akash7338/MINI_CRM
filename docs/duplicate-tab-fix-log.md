# Duplicate Tab Session Handling — Fix Log

---

## Part 1: Implementation of the Spec

This section covers the initial backend and frontend implementation of the single-session-per-user specification (`active-session-per-user-spec.md`) before runtime issues were discovered.

### What the Spec Requires

- **One active session per user**, enforced server-side. When a new login or tab activation occurs, the old session is kicked immediately.
- **"Latest session wins"**: the most recent tab to call `/activate` gets the session; all others are kicked.
- **Token blacklisting via Redis**: old JWTs are blacklisted; any subsequent HTTP request from the old tab returns `HTTP 403 { errorCode: 403020, message: "TOKEN_EXPIRED" }`.
- **Push notification via WebSocket (STOMP)**: a `LogoutNotification` is pushed to the old tab's personal topic (`/topic/{tenantId}/user/{userId}`) near-instantly so the tab doesn't have to wait for an HTTP request to discover it was kicked.
- **No `sessionId` in JWT**: tokens are standard HS256 JWTs identified by a `jti` (UUID) claim. Invalidation targets the specific `jti`, not the user.

---

### Backend Changes

#### `shared-common` — `JwtUtil.java`

- Removed the `sessionId` parameter from `generateToken()`. The method now generates a clean JWT with `userId`, `tenantId`, `role`, `agentId`, and a unique `jti` claim (`UUID.randomUUID().toString()`) added via `setId()`.
- `blacklistKey(String jti)` now returns `"blacklist:{jti}"`. Using the `jti` (rather than `userId_exp`) means two tokens issued within the same millisecond for the same user always have different blacklist keys — eliminating the collision bug where blacklisting one token would also blacklist the other.

#### `user-service` — `UserService.java`

Added the core session management logic:

- **`kickExistingSessionIfPresent(userId, tenantId, skipToken)`**: reads the existing session from Redis (`user:{userId}:session` hash, fields `token` + `loginAt`). If a prior session exists and the token is different from the incoming token, it calls `blacklistToken()` then `sendLogoutNotification()`.
- **`stampSession(userId, token)`**: writes the new token and current timestamp into the Redis session hash with a TTL slightly longer than the JWT expiry.
- **`blacklistToken(token, userId)`**: decodes the JWT, extracts `jti` and `exp`, writes `"blacklist:{jti}" → "1"` to Redis with a TTL matching the token's remaining lifetime. Returns the `jti` so the caller can include it in the logout notification.
- **`sendLogoutNotification(tenantId, userId, reason, kickedJti)`**: publishes a JSON event to the `auth-events` Kafka topic. The payload includes `type`, `tenantId`, `userId`, `reason`, `kickedJti`, and `timestamp`. The `kickedJti` field is critical — it tells the receiving tab whether the notification targets its own token or belongs to a different (older) tab.
- `login()` and `activateSession()` both call `kickExistingSessionIfPresent()` then `stampSession()`.

Added dependencies: `spring-boot-starter-data-redis` and `spring-kafka` in `build.gradle`; Kafka producer config in `application.yml`.

#### `api-gateway` — `JwtAuthenticationFilter.java`

- Removed all `sessionId` claim extraction and the old Redis session-ownership check.
- On every authenticated request: validates the JWT signature, then checks `"blacklist:{jti}"` in Redis. If found → `HTTP 403 { errorCode: 403020, message: "TOKEN_EXPIRED" }` via `onTokenExpired()`.
- The `/activate` endpoint is explicitly bypassed from the blacklist check (it needs to accept the old token to issue a new one).

#### `websocket-gateway` — `KafkaEventConsumer.java`

- Added `auth-events` to the `@KafkaListener` topics.
- `handleAuthEvent()` parses the JSON payload, identifies `LogoutNotification` type events, and calls `convertAndSend()` to `/topic/{tenantId}/user/{userId}` — delivering the notification to all WebSocket subscribers on that user's personal channel.

#### `websocket-gateway` — `AuthChannelInterceptor.java`

- On `STOMP CONNECT`: stores `userId` (extracted from the JWT) in the WebSocket session attributes.
- On `STOMP SUBSCRIBE`: validates user-specific topic subscriptions — a user may only subscribe to their own `/topic/{tenantId}/user/{userId}`, not another user's.

---

### Frontend Changes

#### `ApiService`

- **In-memory token (`_token`)**: the active JWT is held in memory. `localStorage.token` is used only as a bootstrap seed when the app starts. `getTokenForRequest()` returns `_token` if present (and not force-logged out); else falls back to `localStorage`.
- **`setToken(token)`**: writes both `_token` and `localStorage.token`.
- **`notifyForceLogout(reason)`**: sets `_forceLoggedOut = true`, clears `_token`, writes `reason` to `sessionStorage` (per-tab — see Issue 4 below), and emits `sessionRevoked$`.
- **`login()`**: clears `_forceLoggedOut` and `sessionStorage.reason`; uses `setToken()`.
- **`activateSession()`**: calls `POST /activate` with the current token; updates `_token` with the fresh token returned.

#### `main.ts` — Interceptors

- **`authInterceptor`**: attaches the JWT via `api.getTokenForRequest()` (reads in-memory token, not raw localStorage).
- **`forceLogoutInterceptor`**: catches HTTP responses where `status === 403` and `error.errorCode === 403020`, then calls `api.notifyForceLogout('TOKEN_EXPIRED')`. This is the fallback path for tabs that miss the WebSocket push.

#### `WebsocketService`

- Added `userEventsSubject` (`Subject<any>`) and `userEvents$` observable.
- **`subscribeToUserChannel(userId)`**: subscribes to `/topic/{tenantId}/user/{userId}` on the active STOMP connection. If the connection isn't ready yet, the subscription is deferred via `pendingUserChannelId` and processed in the `onConnect` callback.

#### `AppComponent`

- **Bootstrap sequence**:
  1. Check `sessionStorage.reason`. If set → show kick message, `setupSubscriptions()`, stop (don't call activate).
  2. Else if `token` + `role` exist in localStorage → set `isLoggedIn = true` optimistically, call `activateSession()`. On success: subscribe to user channel, restore agent state. On failure: `finalizeLogout()`.
- **`ws.userEvents$` subscriber**: when a `LogoutNotification` arrives, compares `event.kickedJti` with the current in-memory token's `jti` (decoded client-side from the JWT payload). If they match → this tab was kicked → `notifyForceLogout()`. If they differ → this tab is the new winner → notification ignored.
- **`api.sessionRevoked$` subscriber**: HTTP fallback path — shows kick message and logs out.
- **`api.loginSuccess$` subscriber**: clears `kickMessage`, calls `ws.connect()` then `subscribeToUserChannel()`, restores agent state.

---

### Blacklist Collision Bug (Fixed During Smoke Testing)

**Problem**: the initial blacklist key used `userId_exp` (user ID + token expiry epoch). Two tokens generated within the same millisecond for the same user would share identical `exp` values, so blacklisting one token also blacklisted the other — including the freshly-issued winning token.

**Fix**: switched to `jti`-based keys. Every token now carries a `jti` UUID. `blacklistKey(jti)` returns `"blacklist:{jti}"`. Each token has a globally unique key regardless of when it was issued.

---

This document records every issue found and fixed while implementing "single active session per user" (latest session wins) in MiniGenesys. Each issue is documented in the order it was discovered.

---

## Background

The goal: when a logged-in browser tab is duplicated, the **new tab** should continue working and the **original tab** should be logged out. This is enforced server-side — the server detects a re-login, blacklists the old token, and pushes a logout notification to the old tab.

The architecture involves:
- **`/auth/activate`** — called on every tab load (refresh/duplicate). Issues a fresh JWT and kicks any existing session.
- **Token blacklist** — Redis-backed. Old tokens are blacklisted and rejected with `HTTP 403 { errorCode: 403020 }`.
- **WebSocket (STOMP/SockJS)** — pushes `LogoutNotification` to the old tab's personal channel (near-instant).
- **HTTP 403 interceptor** — fallback path. Catches blacklisted-token responses and triggers force-logout.

---

## Issue 1: WebSocket never connects after fresh login

### Symptom

After a fresh credential login (not a page refresh), duplicating the tab did **nothing** — the original tab was not logged out. The only way it would get kicked is if it happened to make an HTTP request (e.g., agent heartbeat), which would trigger the 403 fallback. Idle tabs (especially supervisors) were never kicked.

### Root Cause

`WebsocketService.connect()` is called from the `SessionStateService` constructor, which runs during Angular bootstrap — **before the user has typed their credentials**. At that point, `localStorage.token` doesn't exist, so `connect()` returns immediately without activating the STOMP connection.

```typescript
// SessionStateService constructor (runs at bootstrap)
this.ws.connect();

// WebsocketService.connect()
connect() {
  const token = localStorage.getItem('token');
  if (!token) return;   // ← token doesn't exist yet, exits immediately
  // ...
}
```

After the user logs in and the token is saved, **nobody calls `ws.connect()` again**. The `subscribeToUserChannel()` call in the `loginSuccess$` handler finds the WebSocket disconnected and defers the subscription to `pendingUserChannelId` — but the pending subscription is never fulfilled because the connection is never established.

**Result:** Tab A has no WebSocket connection and no user channel subscription. It can never receive the `LogoutNotification` that Tab B's `/activate` sends.

### Fix

Added `this.ws.connect()` in the `loginSuccess$` handler, before subscribing to the user channel.

**File:** `src/app/app.component.ts`

```typescript
this.api.loginSuccess$.subscribe(success => {
  if (success) {
    // ...
    this.ws.connect();              // ← ADDED: establish WebSocket after login
    this.subscribeToUserChannel();
    // ...
  }
});
```

`connect()` is idempotent (checks `stompClient.active`), so calling it again on page-refresh scenarios where the connection already exists is safe. The `subscribeToUserChannel()` deferred-subscription pattern handles the async connection gracefully.

---

## Issue 2: Kicked tab re-activates on refresh (ping-pong loop)

### Symptom

1. Tab 1 logs in
2. Duplicate → Tab 2 takes over, Tab 1 is logged out (correct)
3. Refresh Tab 1 → **Tab 1 logs back in and kicks Tab 2**
4. Refresh Tab 2 → Tab 2 logs back in and kicks Tab 1
5. Infinite ping-pong

### Root Cause

When Tab 1 is kicked, `notifyForceLogout()` sets `localStorage.reason` but does **not** clear `localStorage.token` (intentionally — clearing it would wipe the winning tab's token since localStorage is shared). So after the kick:

- `localStorage.token` = Tab 2's valid token
- `localStorage.reason` = `'LOGOUT_AGENT'`
- `localStorage.role` = still set

The constructor's defense against re-activation relies on `localStorage.reason`:

```typescript
const kickReason = localStorage.getItem('reason');
if (kickReason) {
  localStorage.removeItem('reason');   // ← REMOVED IMMEDIATELY
  this.kickMessage = '...';
  return;                              // ← don't activate
}
```

The problem: `reason` is removed as soon as it's read. The first refresh works (shows kick message). But if the user refreshes **again**, `reason` is gone, `token` + `role` still exist, and `/activate` fires — stealing the session back.

### Fix

Two changes:

1. **Don't remove `reason` in the constructor.** Keep it persistent so subsequent refreshes stay blocked.

**File:** `src/app/app.component.ts`

```typescript
const kickReason = sessionStorage.getItem('reason');
if (kickReason) {
  // Removed: localStorage.removeItem('reason')
  // reason persists — subsequent refreshes stay on login screen
  this.kickMessage = '...';
  return;
}
```

2. **Clear `reason` only on explicit credential login** — the only legitimate way to re-enter.

**File:** `src/app/services/api.service.ts`

```typescript
login(credentials: any): Observable<any> {
  return this.http.post(...).pipe(
    tap((res: any) => {
      if (res.accessToken) {
        this._forceLoggedOut = false;
        sessionStorage.removeItem('reason');   // ← clear here only
        this.setToken(res.accessToken);
        // ...
      }
    })
  );
}
```

---

## Issue 3: Tab 2 kicks itself (race condition with `/activate`)

### Symptom

1. Tab 1 logs in
2. Duplicate → Tab 2 opens
3. Tab 1 is logged out (correct)
4. Go to Tab 2 → **also logged out**

Both tabs end up on the login screen.

### Root Cause

When Tab 2 opens, Angular bootstraps and two things fire **concurrently** from the constructor chain:

1. **`SessionStateService` constructor** (runs first — it's a dependency of `AppComponent`):
   - Calls `loadInitialState(agentId)` → sends `GET /agents/{id}/state` with **token A** (the old token from localStorage)
   - May start a heartbeat → sends `POST /agents/{id}/heartbeat` with **token A**

2. **`AppComponent` constructor** (runs after):
   - Calls `activateSession()` → sends `POST /activate` with **token A**

Both are async HTTP requests that race to the server. The `/activate` request blacklists token A on the server. If the `getAgentState` or heartbeat request hits the gateway's blacklist check **after** token A has been blacklisted (but before the activate response reaches the client), it gets `403 TOKEN_EXPIRED`:

```
Timeline:

Tab 2 sends:  POST /activate (token A)       GET /agents/{id}/state (token A)
                   |                                    |
Server:      processes activate               gateway blacklist check...
             blacklists token A in Redis
             returns token B                           |
                   |                          Redis: token A is blacklisted!
Tab 2:       _token = B ✓                    → HTTP 403 {errorCode: 403020}
                                              → forceLogoutInterceptor fires
                                              → Tab 2 kicks ITSELF
```

### Fix

Moved `loadInitialState()` and `subscribeToTenantEvents()` out of the `SessionStateService` constructor. They are now called explicitly from `AppComponent` **after** activate/login succeeds (when `_token` is safely set to the new token).

**File:** `src/app/services/session-state.service.ts`

```typescript
// BEFORE (constructor):
const storedAgentId = localStorage.getItem('agentId');
if (storedAgentId) {
  this.patchAgent({ agentId: storedAgentId });
  this.loadInitialState(storedAgentId);       // ← REMOVED from constructor
  this.ws.subscribeToTenantEvents();          // ← REMOVED from constructor
}

// AFTER (constructor):
const storedAgentId = localStorage.getItem('agentId');
if (storedAgentId) {
  this.patchAgent({ agentId: storedAgentId });
  // loadInitialState + subscribeToTenantEvents moved to AppComponent
}
```

`loadInitialState` was changed from `private` to `public`.

**File:** `src/app/app.component.ts`

```typescript
// Called after activate/login succeeds
private restoreAgentState() {
  const agentId = localStorage.getItem('agentId');
  if (agentId) {
    this.session.loadInitialState(agentId);
    this.ws.subscribeToTenantEvents();
  }
}

// In activate success handler:
this.api.activateSession().subscribe({
  next: () => {
    this.isLoggedIn = true;
    this.subscribeToUserChannel();
    this.restoreAgentState();              // ← safe: _token is set
    this.initializeTelephony(...);
  }
});

// In loginSuccess$ handler:
this.api.loginSuccess$.subscribe(success => {
  if (success) {
    // ...
    this.restoreAgentState();              // ← safe: _token is set
    // ...
  }
});
```

---

## Issue 4: Winning tab (Tab 2) gets logged out on refresh

### Symptom

1. Tab 1 logs in
2. Duplicate → Tab 2 takes over, Tab 1 is logged out (correct)
3. Refresh Tab 1 → stays logged out (correct — fix #2 working)
4. Refresh Tab 2 → **Tab 2 gets logged out**

### Root Cause

`localStorage.reason` is **shared across all tabs**. When Tab 1 is kicked, it sets `localStorage.reason = 'LOGOUT_AGENT'`. When Tab 2 refreshes, the constructor reads the same shared `localStorage.reason`, sees `'LOGOUT_AGENT'`, and thinks **it** was the one kicked.

```
Tab 1 kicked → localStorage.reason = 'LOGOUT_AGENT'   (shared storage)
Tab 2 refreshes → reads localStorage.reason → 'LOGOUT_AGENT' → thinks it's kicked!
```

### Fix

Switched the `reason` flag from `localStorage` (shared across tabs) to `sessionStorage` (per-tab, survives refresh, doesn't bleed into other tabs).

| Property | `localStorage` (before) | `sessionStorage` (after) |
|---|---|---|
| Shared across tabs? | Yes | **No — per-tab** |
| Survives page refresh? | Yes | Yes |
| Survives tab close + reopen? | Yes | No (fresh start) |
| Duplicated tab inherits it? | N/A (shared) | Yes, but `reason` isn't set at duplication time |

**Files changed:** `api.service.ts` and `app.component.ts`

Every `localStorage.getItem('reason')` / `localStorage.setItem('reason', ...)` / `localStorage.removeItem('reason')` was changed to use `sessionStorage` instead. Specifically:

- `notifyForceLogout()` → `sessionStorage.setItem('reason', reason)`
- `login()` → `sessionStorage.removeItem('reason')`
- `logout()` → `sessionStorage.removeItem('reason')`
- Constructor → `sessionStorage.getItem('reason')`
- `sessionRevoked$` subscriber → `sessionStorage.getItem('reason')`

---

## Issue 5: Flash of login screen on refresh

### Symptom

On every page refresh, the login screen flashes briefly (milliseconds) before the dashboard appears.

### Root Cause

`isLoggedIn` defaults to `false`. The template renders `*ngIf="!isLoggedIn"` → shows login. Then the async `/activate` call completes and sets `isLoggedIn = true`. The login screen is visible during the HTTP round-trip.

### Fix

Set `isLoggedIn = true` **optimistically** before calling activate, when we know `token` + `role` exist and no `kickReason` is present. If activate fails, `finalizeLogout()` sets it back to `false`.

**File:** `src/app/app.component.ts`

```typescript
if (token && role) {
  this.role = role;
  this.view = role === 'SUPERVISOR' ? 'overview' : 'workspace';
  this.isLoggedIn = true;           // ← MOVED: set before activate (was inside subscribe.next)

  this.api.activateSession().subscribe({
    next: () => {
      // isLoggedIn already true — no flash
      this.subscribeToUserChannel();
      this.restoreAgentState();
    },
    error: () => {
      this.finalizeLogout();        // sets isLoggedIn = false
    }
  });
}
```

This is safe because fix #3 ensured no API calls (heartbeats, state loads) fire until after activate returns, so the dashboard is inert during the round-trip.

---

## Summary of All Fixes

| # | Issue | Root Cause | Fix | Files |
|---|---|---|---|---|
| 1 | WebSocket never connects after login | `ws.connect()` called before token exists | Call `ws.connect()` in `loginSuccess$` handler | `app.component.ts` |
| 2 | Kicked tab re-activates on refresh | `localStorage.reason` removed immediately | Keep `reason` persistent; clear only on credential login | `app.component.ts`, `api.service.ts` |
| 3 | Tab 2 kicks itself | `loadInitialState` races with `/activate` | Move `loadInitialState` to after activate/login | `session-state.service.ts`, `app.component.ts` |
| 4 | Winning tab logged out on refresh | `localStorage.reason` shared across tabs | Switch `reason` to `sessionStorage` (per-tab) | `api.service.ts`, `app.component.ts` |
| 5 | Flash of login screen on refresh | `isLoggedIn` defaults to `false` | Set `isLoggedIn = true` optimistically before activate | `app.component.ts` |
