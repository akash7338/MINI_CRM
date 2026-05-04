# Gateway Deep Dive: Routing & Security

This guide explains how the API Gateway handles browser security (CORS) and traffic routing.

---

## 1. What is CORS?
CORS (Cross-Origin Resource Sharing) is a **browser-level security mechanism**. It acts like a "Trusted Friends List" for your server.

**The Problem:** By default, if a website at `localhost:4200` (Angular) tries to talk to a server at `localhost:8080` (Gateway), the browser will block it because they are on different ports (different origins).

---

## 2. YAML Field Breakdown
```yaml
globalcors:
  cors-configurations:
    '[/**]':                     # 1. The Catch-All Path
      allowedOrigins:            # 2. The Guest List
        - "http://localhost:4200"
      allowedMethods: "*"        # 3. Allowed Actions
      allowedHeaders: "*"        # 4. Allowed Extra Info
      allowCredentials: true     # 5. Trust Sensitive Data
```

1.  **`'[/**]'`**: Applies these rules to **every single URL path** in the system.
2.  **`allowedOrigins`**: The whitelist. If a request comes from `evil-hacker.com`, the Gateway sees it's not on this list and tells the browser to block it.
3.  **`allowedMethods`**: Allows GET, POST, PUT, DELETE, etc.
4.  **`allowedHeaders`**: Allows custom headers like `Authorization` (JWT) and `X-Tenant-Id`.
5.  **`allowCredentials`**: Allows the browser to send the JWT token. If `false`, login would fail.

---

## 3. The Mapping Mechanism (How it knows the Path)
How does the Gateway know the path starts at `/api` and not `http`?

### URL Anatomy:
`http://` (Protocol) + `localhost:8080` (Host) + `/api/v1/users` (Path)

1.  **Connection:** The browser uses the Host/Port to find the server.
2.  **Message:** Once connected, the browser sends a raw HTTP message: `GET /api/v1/users HTTP/1.1`.
3.  **Extraction:** The Gateway (Netty/Spring) is programmed to treat everything **after the first forward slash (`/`)** following the port as the **Path**.

---

## 4. Specificity: The "Longest Prefix" Rule
Spring Cloud Gateway uses a **"Most Specific Wins"** principle. If multiple patterns match a single URL, the Gateway doesn't just pick the first one; it picks the one that is the best match.

**How "Best Match" is calculated:**
The Gateway looks for the **Longest Literal Path** (the longest string before the wildcard `*`).

**Example Case:**
Incoming URL: `/admin/api/routing/v1`

Possible Matches:
1.  `'/admin/*'` (Literal length: 7)
2.  `'/admin/api/*'` (Literal length: 11)
3.  **`'/admin/api/routing/*'`** (**WINNER** — Literal length: 19)

**Why?** The Gateway assumes that if you took the time to define a very deep path like `/admin/api/routing/*`, you want that specific configuration to take priority over a generic catch-all like `/admin/*`.

---

## 5. Why handle CORS at the Gateway?
1.  **Centralized Control:** Change the whitelist in one place instead of 9 services.
2.  **The "Double Header" Problem:** If both the Gateway and a Microservice add a CORS header, the browser receives two `Access-Control-Allow-Origin` headers. The browser gets confused and **crashes the request**.
3.  **The Safety Net:** The `DedupeResponseHeader` filter in the Gateway ensures that if duplicate headers appear, it keeps only the first one (`RETAIN_FIRST`).

---

## 6. Multiple Paths per Route
A single route can be configured to handle multiple distinct URL patterns using a comma-separated list in the `Path` predicate.

**Example from your `application.yml`:**
```yaml
- id: user-service
  predicates:
    - Path=/api/v1/auth/**, /api/v1/users/**
```

**How it works:**
*   **Logical OR:** The Gateway treats the comma as an "OR" condition. If the request matches **either** path, it will be routed.
*   **Efficiency:** This allows you to group related functional paths (like Authentication and User Profile management) under a single microservice without duplicating the route configuration.
*   **Shared Logic:** Any filters (like `JwtAuthenticationFilter`) applied to this route ID will automatically apply to all paths listed in that predicate.

---

## 7. The Internal Block (Security via Routing)
The Gateway can act as a **Security Firewall** by blocking specific "Internal-Only" paths before they ever reach your microservices.

**Example from your `application.yml`:**
```yaml
- id: agent-state-internal-block
  predicates:
    - Path=/api/v1/agents/internal
  filters:
    - SetStatus=403
```

**Key Concepts:**
*   **Why use it?** Some endpoints are designed for service-to-service communication (e.g., User Service calling Agent Service). These often don't require JWTs. The Gateway ensures a browser user cannot "guess" the URL and bypass security.
*   **403 Forbidden:** The Gateway immediately kills the request and returns a 403 status.
*   **Specificity at Work:** Because the `/internal` path is **more specific** than the general `/agents/**` path, the Gateway's "Longest Prefix" rule ensures this block is checked first.

---

## 8. User JWT vs. Internal Service Keys
A common interview question is: *"Do your microservices use JWTs to talk to each other?"*

**The Answer:** No. JWTs are for **Users**. Services use **Internal Keys** or **Trusted Network** communication.

| Flow Type | Authentication Used | Why? |
| :--- | :--- | :--- |
| **Browser → Gateway** | **JWT Token** | To verify the specific user's identity. |
| **Service A → Service B** | **Internal Key** | To verify that the caller is a trusted part of our backend. |

### Real Examples from the code:
1.  **User Service → Agent State Service:** Uses an `X-Internal-Key` header. We can't use a JWT here because when an admin is creating a new agent, that agent doesn't have a token yet!
2.  **Telephony Service → Call Service:** Uses trusted network communication (passing only `X-Tenant-Id`). Since inbound phone calls are anonymous, there is no JWT to provide.

**Security Implication:** This is exactly why the **Internal Block (Section 7)** is so important. Since internal APIs use simpler keys instead of complex JWTs, we must block them at the Gateway so they aren't exposed to the public internet.

---

## 9. The Lifecycle of a Request (Step-by-Step Trace)
**Example:** A user attempts to login: `POST http://localhost:8080/api/v1/auth/login`

1.  **Entry (Port 8080):** The browser hits the Gateway's port (defined in `server.port`). The internal Netty server picks up the raw request.
2.  **CORS Check:** The Gateway checks the `globalcors` configuration. Since the path matches `[/**]`, it validates that the browser's origin (`localhost:4200`) is in the `allowedOrigins` list.
3.  **Global Filter (The Bouncer):** The request enters the `filter()` method of `JwtAuthenticationFilter.java`. 
    *   The filter checks the `OPEN_ENDPOINTS` list.
    *   Since `/api/v1/auth/login` is marked as an open endpoint, it skips JWT validation and calls `chain.filter(exchange)`.
4.  **Predicate Matching:** The Gateway compares the path against the `routes:` list in `application.yml`.
    *   It finds a match in the `user-service` route because the path matches the `Path=/api/v1/auth/**` predicate.
5.  **Proxying (Forwarding):** The Gateway looks up the destination `uri`: `http://localhost:8090`. It acts as a middleman and sends a new request to the User Service.
6.  **Microservice Processing:** The User Service (running on port 8090) processes the login logic and returns a response (the JWT).
7.  **Response Return:** The Gateway receives the response from the microservice, applies its `default-filters` (like `DedupeResponseHeader`), and delivers the final result back to the user's browser.
