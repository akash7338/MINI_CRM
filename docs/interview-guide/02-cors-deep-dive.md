# Revision: CORS & API Gateway Routing

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
