# boomerang-standalone

Runnable Boomerang service for **standalone mode**. Deploy this as infrastructure and point any application's `workerUrl` at it — regardless of language or framework.

Unlike the embedded library (`boomerang-starter`), this module contains no `@BoomerangHandler`. All job dispatch happens over HTTP: Boomerang calls your `workerUrl`, captures the response, and fires the webhook callback. Your application never needs to pull in the Java library.

---

## Quick start

```bash
docker compose up
```

This starts Boomerang on port `8080` and Redis on port `6379`. Edit `docker-compose.yml` to set your JWT secret and allowed domains before running in anything beyond local dev.

---

## Docker

The image is published to GitHub Container Registry on every `v*` tag:

```bash
docker pull ghcr.io/sameerchereddy/boomerang:latest
```

Run it directly:

```bash
docker run -d \
  -p 8080:8080 \
  -e BOOMERANG_JWT_SECRET=your-secret-min-32-chars \
  -e SPRING_DATA_REDIS_HOST=your-redis-host \
  -e BOOMERANG_CALLBACK_ALLOWED_DOMAINS=myapp.example.com \
  ghcr.io/sameerchereddy/boomerang:latest
```

---

## How it works

```
Your app  →  POST /sync  { workerUrl, callbackUrl, ... }
                ↓  202 + jobId  (< 50 ms)
            [Boomerang queues job]
                ↓  POST workerUrl  { jobId, triggeredAt }
            Your app processes and returns result in HTTP response body
                ↓  POST callbackUrl  { jobId, status, result }
```

1. Your app triggers a job by POSTing to `/sync` with a `workerUrl` pointing at your own endpoint.
2. Boomerang returns `202` immediately with a `jobId`.
3. Boomerang calls your `workerUrl` — you process the work and return the result as the HTTP response body.
4. Boomerang fires your `callbackUrl` with the result.

---

## Triggering a job

```bash
curl -X POST http://localhost:8080/sync \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "workerUrl":      "https://myapp.example.com/internal/do-sync",
    "callbackUrl":    "https://myapp.example.com/hooks/sync-done",
    "callbackSecret": "optional-hmac-secret-min-32-chars",
    "idempotencyKey": "optional-dedup-key",
    "payload":        { "any": "data" }
  }'
# → 202 { "jobId": "..." }
```

## Worker endpoint contract

Boomerang calls your `workerUrl` with:

```
POST https://myapp.example.com/internal/do-sync
X-Boomerang-Job-Id:  <jobId>
X-Signature-SHA256:  sha256=<hmac-hex>   (only when callbackSecret was provided)
Content-Type:        application/json

{ "jobId": "...", "triggeredAt": "2026-03-30T10:00:00Z" }
```

- Return **any 2xx** — Boomerang captures the response body as the job result.
- Return **any non-2xx** — Boomerang retries up to `boomerang.worker.max-attempts` times, then marks the job `FAILED` and fires the callback with `"status": "FAILED"`.

---

## Configuration

All settings are driven by environment variables. Full list:

| Environment variable | Default | Description |
|---|---|---|
| `BOOMERANG_JWT_SECRET` | *(required)* | HS256 signing secret — min 32 chars |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis hostname |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis port |
| `BOOMERANG_CALLBACK_ALLOWED_DOMAINS` | *(empty)* | Comma-separated allowed domains for `callbackUrl` and `workerUrl` |
| `BOOMERANG_SKIP_URL_VALIDATION` | `false` | Bypass SSRF checks — local dev only |
| `BOOMERANG_IDEMPOTENCY_COOLDOWN_SECONDS` | `300` | Duplicate request window |
| `BOOMERANG_THREAD_POOL_CORE_SIZE` | `5` | Worker thread pool core size |
| `BOOMERANG_THREAD_POOL_MAX_SIZE` | `20` | Worker thread pool max size |
| `BOOMERANG_THREAD_POOL_QUEUE_CAPACITY` | `100` | Jobs queued before pool rejects |
| `BOOMERANG_WEBHOOK_MAX_ATTEMPTS` | `5` | Callback delivery retry attempts |
| `BOOMERANG_WEBHOOK_INITIAL_BACKOFF_MS` | `1000` | Initial retry backoff (ms) |
| `BOOMERANG_WEBHOOK_MAX_BACKOFF_MS` | `30000` | Max retry backoff (ms) |
| `BOOMERANG_WORKER_MAX_ATTEMPTS` | `3` | Worker invocation retry attempts |
| `BOOMERANG_WORKER_TIMEOUT_SECONDS` | `300` | Per-attempt timeout for worker calls |
| `BOOMERANG_WORKER_MAX_RESPONSE_SIZE_BYTES` | `10485760` | Max worker response body size (10 MB) |

---

## API

Base path is `/sync`. All endpoints require `Authorization: Bearer <jwt>`.

| Method | Path | Description |
|---|---|---|
| `POST` | `/sync` | Enqueue a job — returns `202 { jobId }` |
| `GET` | `/sync/{jobId}` | Poll job status |
| `GET` | `/sync/failed-webhooks` | List dead-lettered webhook deliveries |
| `POST` | `/sync/failed-webhooks/{jobId}/replay` | Re-attempt a failed delivery |
| `DELETE` | `/sync/failed-webhooks/{jobId}` | Discard a failed delivery |

---

## Building the image locally

```bash
mvn package -pl boomerang-standalone -am -DskipTests
docker build -t boomerang:local boomerang-standalone/
```
