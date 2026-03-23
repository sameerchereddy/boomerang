# Boomerang — Multi-Language Roadmap

> *This document captures the architectural plan for evolving Boomerang from a Java Spring Boot embedded library into a language-agnostic, open source async webhook platform. It is a future-facing document — the current focus is the Java embedded implementation.*

**Status:** Future planning — not active
**Depends on:** `boomerang-spring-boot-starter` v1.0 stable
**Last updated:** 2026-03-22

---

## The Vision

Boomerang starts as a Java Spring Boot starter. The end goal is for any developer — regardless of language or framework — to be able to turn a slow, blocking HTTP endpoint into a fast async API with webhook callbacks, with minimal integration effort.

> One platform. Any language. Same contract.

---

## Why the Embedded Model Doesn't Scale to Multi-Language

The current Java design is an **embedded library** — Boomerang lives inside the consumer's Spring Boot app. The `@BoomerangHandler` function runs in Boomerang's own thread pool, within the same JVM.

This works perfectly for Java. It doesn't translate to other languages because:

- You cannot embed a Spring Boot starter in a Node.js, Python, or Go application
- Each language has fundamentally different concurrency models (event loop, GIL, goroutines)
- Maintaining a full Boomerang implementation in 4+ languages means 4x the bugs, 4x the Redis schema drift, 4x the security patching

The solution is a **mode shift**.

---

## The Architecture Shift: Standalone Service + Thin SDKs

Instead of an embedded library per language, Boomerang becomes a **standalone service** — a deployable unit that any application can talk to over HTTP.

```
┌──────────────────────────────────────────────────────────────────┐
│                       Boomerang Service                          │
│                  (Java · Spring Boot · Redis)                    │
│                                                                  │
│  Owns: job queuing · idempotency · worker invocation ·           │
│        webhook dispatch · retry · HMAC signing · metrics         │
└────────────────────────┬─────────────────────────────────────────┘
                         │  HTTP (same API contract as today)
          ┌──────────────┼──────────────────────┐
          │              │                      │
  ┌───────▼──────┐ ┌─────▼──────┐ ┌────────────▼───┐ ┌──────────┐
  │  Java SDK    │ │  Node SDK  │ │  Python SDK    │ │  Go SDK  │
  │  (embedded   │ │  (npm)     │ │  (pip)         │ │ (module) │
  │   or client) │ │            │ │                │ │          │
  └──────────────┘ └────────────┘ └────────────────┘ └──────────┘
```

### What the Boomerang service owns (written once, in Java)

- Redis job queue and polling loop
- Per-caller idempotency locking
- Worker invocation (calling the consumer's `workerUrl`)
- Webhook dispatch with exponential retry
- HMAC signature generation
- JWT validation
- Micrometer metrics
- Dead letter store and replay

### What each thin SDK owns (written per language, small)

- HTTP client wrapper for `POST /sync` and `GET /sync/{jobId}`
- JWT generation helpers (or guidance on using the language's standard library)
- HMAC signature verification for incoming webhooks
- Webhook receiver middleware/decorator for common frameworks
- Error types and response models idiomatic to the language

The SDKs contain **no queue logic, no Redis dependency, no retry logic** — all of that lives in the Boomerang service. This is the key insight that keeps SDK maintenance light.

---

## How the Handler Model Changes

### Embedded mode (Java — current)

The handler function lives inside Boomerang's own process:

```java
@BoomerangHandler
public Object doSync(SyncContext ctx) {
    // runs inside Boomerang's thread pool
    return result;
}
```

### Standalone mode (all languages)

The consumer's existing slow endpoint becomes the `workerUrl`. Boomerang calls it over HTTP when it pops the job from the queue. The consumer processes it and returns the result synchronously — Boomerang then fires the webhook.

```
POST /sync  ──►  Boomerang queues job, returns 202 + jobId
                 Boomerang calls workerUrl (consumer's existing endpoint)
                 Consumer processes, returns result in HTTP response body
                 Boomerang fires callbackUrl webhook with result
```

**No changes to the consumer's existing slow endpoint are required.** It just needs to accept a `jobId` in the request body and return the result. Boomerang handles everything else.

### API contract addition for standalone mode

```http
POST /sync
Authorization: Bearer <token>
Content-Type: application/json

{
  "workerUrl":      "https://myapp.com/internal/do-sync",
  "callbackUrl":    "https://myapp.com/hooks/sync-done",
  "callbackSecret": "optional-hmac-secret",
  "idempotencyKey": "optional-caller-scoped-key"
}
```

`workerUrl` is the new field. In embedded mode (Java), it is absent — Boomerang invokes the registered `@BoomerangHandler` function instead. In standalone mode it is required.

`workerUrl` is subject to the same SSRF allowlisting as `callbackUrl`.

### Worker invocation payload (Boomerang → consumer)

```json
POST https://myapp.com/internal/do-sync
X-Boomerang-Job-Id: 550e8400-e29b-41d4-a716-446655440000
X-Signature-SHA256: sha256=<hmac-hex>
Content-Type: application/json

{
  "jobId":       "550e8400-e29b-41d4-a716-446655440000",
  "triggeredAt": "2026-03-22T10:00:00Z"
}
```

The consumer returns the result in the HTTP response body (200 OK). Boomerang captures it and fires the `callbackUrl` webhook.

---

## Dual-Mode Support

The Java Spring Boot implementation supports **both modes simultaneously** — embedded for Java users who want zero extra infrastructure, standalone for everyone else.

| Mode | Who uses it | Handler | Extra infra |
|---|---|---|---|
| **Embedded** | Java / Spring Boot | `@BoomerangHandler` function | None — Boomerang runs inside the app |
| **Standalone** | Any language | `workerUrl` HTTP endpoint | Boomerang service + Redis |

Same codebase. Same Redis schema. Same webhook contract. The mode is determined by whether `workerUrl` is present in the request.

---

## Thin SDK Design (Per Language)

### What every SDK must provide

| Component | Description |
|---|---|
| `trigger(workerUrl, callbackUrl, ...)` | Wraps `POST /sync` — handles auth header, request model |
| `poll(jobId)` | Wraps `GET /sync/{jobId}` — returns typed status model |
| `verify(request, secret)` | Verifies `X-Signature-SHA256` on incoming webhooks |
| Webhook middleware | Framework-specific middleware that calls `verify()` automatically |
| Error types | Typed errors for `401`, `409`, `403`, `503` responses |

### Node.js (npm: `boomerang-client`)

```javascript
import { BoomerangClient } from 'boomerang-client';

const boomerang = new BoomerangClient({
    baseUrl: 'https://boomerang.your-org.com',
    token:   process.env.BOOMERANG_JWT
});

// Trigger
const { jobId } = await boomerang.trigger({
    workerUrl:    'https://myapp.com/internal/do-sync',
    callbackUrl:  'https://myapp.com/hooks/sync-done',
    callbackSecret: process.env.WEBHOOK_SECRET
});

// Express webhook middleware
app.post('/hooks/sync-done', boomerang.webhookMiddleware(process.env.WEBHOOK_SECRET), (req, res) => {
    const { jobId, status, result } = req.boomerangPayload;
    res.sendStatus(200);
});
```

### Python (pip: `boomerang-client`)

```python
from boomerang import BoomerangClient, boomerang_webhook

client = BoomerangClient(
    base_url=os.environ["BOOMERANG_URL"],
    token=os.environ["BOOMERANG_JWT"]
)

# Trigger
job = client.trigger(
    worker_url="https://myapp.com/internal/do-sync",
    callback_url="https://myapp.com/hooks/sync-done",
    callback_secret=os.environ["WEBHOOK_SECRET"]
)

# FastAPI webhook receiver decorator
@app.post("/hooks/sync-done")
@boomerang_webhook(secret=os.environ["WEBHOOK_SECRET"])
async def on_sync_done(payload: BoomerangPayload):
    ...  # signature already verified
```

### Go (module: `github.com/yourhandle/boomerang-go`)

```go
client := boomerang.NewClient(boomerang.Config{
    BaseURL: os.Getenv("BOOMERANG_URL"),
    Token:   os.Getenv("BOOMERANG_JWT"),
})

// Trigger
job, err := client.Trigger(ctx, boomerang.TriggerRequest{
    WorkerURL:      "https://myapp.com/internal/do-sync",
    CallbackURL:    "https://myapp.com/hooks/sync-done",
    CallbackSecret: os.Getenv("WEBHOOK_SECRET"),
})

// net/http webhook handler
http.HandleFunc("/hooks/sync-done", boomerang.WebhookHandler(
    os.Getenv("WEBHOOK_SECRET"),
    func(payload boomerang.Payload) {
        // signature already verified
    },
))
```

---

## Deployment

### Docker (primary distribution artifact)

```bash
docker run -d \
  -p 8080:8080 \
  -e BOOMERANG_JWT_SECRET=your-secret \
  -e SPRING_DATA_REDIS_HOST=your-redis-host \
  -e BOOMERANG_CALLBACK_ALLOWED_DOMAINS=your-org.com \
  ghcr.io/yourhandle/boomerang:latest
```

### Docker Compose (for local development)

```yaml
services:
  boomerang:
    image: ghcr.io/yourhandle/boomerang:latest
    ports:
      - "8080:8080"
    environment:
      BOOMERANG_JWT_SECRET: dev-secret-min-32-chars-long
      SPRING_DATA_REDIS_HOST: redis
      BOOMERANG_CALLBACK_ALLOWED_DOMAINS: localhost,host.docker.internal
    depends_on:
      - redis

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
```

### Kubernetes (production)

A Helm chart is the natural distribution mechanism. Key considerations:
- Boomerang is stateless (all state in Redis) — horizontal scaling is straightforward
- Redis should be external (ElastiCache, Redis Cloud, etc.) in production
- JWT secret and webhook secrets should be injected via Kubernetes Secrets

---

## What Must Be Locked Down Before Multi-Language

These are the cross-language contracts. Changing them after multiple SDKs exist means breaking changes everywhere.

### 1. Redis key schema (stable, versioned)

```
boomerang-job:{jobId}                   Hash — job metadata
boomerang-jobs:pending                  List — dispatch queue
boomerang-lock:{idempotencyKey}         String — idempotency lock
boomerang-failed-webhook:{jobId}        Hash — dead letter store
```

Add `schemaVersion: "1"` to every job hash from day one. If the schema changes, increment the version and handle migration in the service.

### 2. Webhook payload contract

```json
{
  "boomerangVersion": "1",
  "jobId":            "550e8400-e29b-41d4-a716-446655440000",
  "status":           "DONE | FAILED",
  "completedAt":      "2026-03-22T10:00:18Z",
  "result":           { ... },
  "error":            "message if FAILED"
}
```

`boomerangVersion` allows SDKs to handle future payload changes gracefully.

### 3. HMAC format (exact, no variation)

```
Header:  X-Signature-SHA256
Value:   sha256=<lowercase hex>
Input:   raw JSON request body bytes (UTF-8)
Algorithm: HmacSHA256
```

Document this precisely. A single variation (uppercase hex, different header name) silently breaks webhook verification across all language ports.

### 4. Worker invocation contract

```
Method:  POST
Headers: X-Boomerang-Job-Id, X-Signature-SHA256, Content-Type: application/json
Body:    { "jobId": "...", "triggeredAt": "..." }
Success: Any 2xx — response body captured as job result
Failure: Any non-2xx — job marked FAILED, webhook fired with error
```

### 5. JWT claims

```
Algorithm: HS256
Required:  sub (caller identity), exp (expiry)
Optional:  any additional claims — Boomerang ignores unknown claims
```

---

## Limitations to Be Aware Of

### Extra infrastructure
In standalone mode, consumers must deploy and operate the Boomerang service and Redis. This is an adoption barrier for smaller teams. Mitigate with a well-maintained Docker image and Helm chart.

### Worker roundtrip latency
In standalone mode, Boomerang calls the consumer's `workerUrl` over HTTP. This adds a network hop and means Boomerang needs a retry policy for the worker call (not just the webhook). If the consumer's internal endpoint is down, the job cannot be processed. Design decision needed: how many times does Boomerang retry the `workerUrl` before marking the job `FAILED`?

### Concurrency model differences
The thread pool model (Java) doesn't map directly to other runtimes:
- Node.js: consumer's `workerUrl` handler is async/non-blocking — fine
- Python: if the worker is CPU-bound, a single Gunicorn worker will block. Consumer's responsibility to use multiple workers or async
- Go: goroutines handle this naturally

Boomerang doesn't need to solve this — it's the consumer's concern. Document it clearly.

### SDK maintenance
Thin SDKs are small but not zero-cost. Every auth change, payload addition, or new response code needs to be reflected in all SDKs. Mitigate with a shared integration test suite (see below).

---

## Shared Integration Test Suite

This is the key to maintaining parity across all SDKs without manually reading every codebase.

A Docker Compose environment (`boomerang-integration-tests/`) runs:
- A real Boomerang service
- A real Redis instance
- A WireMock server simulating consumer `workerUrl` and `callbackUrl` endpoints

Each SDK's test suite runs against this environment and must pass the same scenario list:

```
✓ Trigger returns 202 + jobId within 50ms
✓ 401 on missing JWT
✓ 401 on expired JWT
✓ 409 on duplicate job within cooldown
✓ 403 on callbackUrl not in allowlist
✓ 503 when worker pool saturated
✓ Worker is called with correct payload
✓ Webhook fires on success with correct payload
✓ Webhook fires on failure with error
✓ X-Signature-SHA256 is present and verifiable
✓ GET /sync/{jobId} returns correct status
✓ GET /sync/{jobId} returns 404 for another caller's job
✓ Webhook retried up to 5 times on consumer failure
✓ Dead letter entry created after 5 failures
```

Any SDK that passes all scenarios is considered compliant. This replaces the need to read four codebases.

---

## Phased Rollout Plan

### Phase 1 — Java embedded (current focus)
- Build and stabilise `boomerang-spring-boot-starter`
- Lock down Redis schema, webhook payload, HMAC format, JWT contract
- Publish to Maven Central
- Write the integration test suite (this becomes the compliance suite for all future SDKs)

### Phase 2 — Standalone mode
- Add `workerUrl` support to the Java service
- Add `workerUrl` allowlisting (same SSRF protection as `callbackUrl`)
- Add retry policy for worker invocation
- Publish Docker image to GitHub Container Registry
- Write Helm chart

### Phase 3 — Node.js SDK
- First thin SDK — Node is the highest-demand non-Java ecosystem
- Express and Fastify middleware for webhook receivers
- Publish to npm

### Phase 4 — Python SDK
- FastAPI decorator + Flask/Django middleware
- Publish to PyPI

### Phase 5 — Go SDK
- `net/http` handler + Gin/Echo middleware
- Publish as Go module

### Phase 6 — Managed cloud option (optional, long-term)
- If adoption warrants it: hosted Boomerang (no self-hosting required)
- Consumers just get an API key and call `api.boomerang.dev/sync`
- SDKs unchanged — just point `baseUrl` at the hosted instance

---

## Open Questions (To Resolve in Phase 2)

| Question | Options | Notes |
|---|---|---|
| How many times does Boomerang retry `workerUrl` before FAILED? | 3 / 5 / configurable | Should mirror the webhook retry policy |
| Does `workerUrl` go through the same allowlist as `callbackUrl`? | Yes (recommended) | Prevents SSRF via worker invocation |
| Should `workerUrl` responses be size-limited? | Yes — e.g. 10MB max | Prevents memory issues on large results |
| Should the worker call have a configurable timeout? | Yes | Long-running jobs need a sensible default (e.g. 5 min) |
| Should embedded mode and standalone mode be separate Spring profiles? | Yes / No | Cleaner separation but adds config surface |
