# Boomerang

[![Maven Central](https://img.shields.io/maven-central/v/io.github.sameerchereddy/boomerang-starter?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.sameerchereddy/boomerang-starter)
[![npm](https://img.shields.io/npm/v/@sameerchereddy/boomerang-client?label=npm)](https://www.npmjs.com/package/@sameerchereddy/boomerang-client)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Changelog](https://img.shields.io/badge/changelog-CHANGELOG.md-informational)](CHANGELOG.md)

Like a boomerang — you throw something out and it comes back to you.

You POST a request. Boomerang returns a `202` in under 50 ms. Your handler runs in the background. When it's done, the result flies back to whatever URL you gave it. That's it.

No polling loops. No managing background threads. No reinventing the webhook wheel.

---

## What it is

A Spring Boot starter that turns any method into an async, webhook-delivering job. Drop in the dependency, annotate one method, point it at Redis, and you have a production-grade async job system — JWT auth, idempotency, exponential-backoff retries, dead-letter storage, and SSRF protection included.

```
Client → POST /jobs { callbackUrl }
           ↓ 202 + jobId  (< 50 ms)
       [background]
           ↓ your @BoomerangHandler runs
           ↓ POST callbackUrl { jobId, status, result }
```

---

## Where it fits

Any operation where making the caller wait is the wrong answer:

- **Report generation** — trigger a PDF/Excel export, get a webhook when the file is ready to download
- **Data pipelines** — kick off an ETL run, get notified when it finishes (or fails)
- **ML inference** — submit a batch scoring job, receive results without holding a connection open
- **Media processing** — transcode a video, resize a batch of images, run OCR on a document
- **Third-party API calls** — call a slow external service and fan results back when it responds
- **Nightly reconciliation** — run a scheduled sync job, push a webhook to your ops tooling when done

In each case the pattern is the same: POST a request, get a `202` back instantly, receive the result at your callback URL when the work is done.

---

## Quick start

**1. Add the dependency**

> Replace `VERSION` with the latest version shown in the Maven Central badge above.

```xml
<dependency>
    <groupId>io.github.sameerchereddy</groupId>
    <artifactId>boomerang-starter</artifactId>
    <version>VERSION</version>
</dependency>
```

**2. Enable Boomerang**

```java
@SpringBootApplication
@EnableBoomerang
public class ReportingService {
    public static void main(String[] args) {
        SpringApplication.run(ReportingService.class, args);
    }
}
```

**3. Write your handler**

Boomerang requires exactly one `@BoomerangHandler` in the application. The app will refuse to start if there are zero or more than one.

```java
@Component
public class ReportHandler {

    @BoomerangHandler
    public Map<String, Object> generate(SyncContext ctx) {
        // ctx.getJobId()          — JobId value object; call .toString() or .value() for the raw string
        // ctx.getCallerId()       — JWT sub claim (who requested it)
        // ctx.getTriggeredAt()    — Instant the worker picked up this job
        // ctx.getPayload()        — JsonNode of the caller-supplied payload, or null if none was sent
        // ctx.getMessageVersion() — caller-supplied schema version string, or null if not sent
        //                           use this to handle payload shape changes safely mid-queue

        JsonNode payload = ctx.getPayload();
        String version = ctx.getMessageVersion(); // e.g. "v1", "v2", or null

        String reportType = payload != null ? payload.get("reportType").asText() : "default";

        String reportUrl = reportService.generate(reportType);
        return Map.of("reportUrl", reportUrl, "generatedBy", ctx.getCallerId());
    }
}
```

**4. Configure**

The endpoint path defaults to `/jobs` but you can name it anything that fits your domain:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

boomerang:
  base-path: /reports           # → POST /reports, GET /reports/{jobId}, etc.
  auth:
    jwt-secret: ${BOOMERANG_JWT_SECRET}   # min 32 chars, HS256
  callback:
    allowed-domains:
      - your-service.example.com          # SSRF allowlist
```

**5. Call it**

```bash
curl -X POST http://localhost:8080/reports \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "callbackUrl": "https://your-service.example.com/hooks/report-ready",
    "callbackSecret": "optional-hmac-secret-min-32-chars!!",
    "idempotencyKey": "optional-dedup-key",
    "payload": { "reportType": "monthly-revenue", "month": "2026-02" }
  }'
# → 202 { "jobId": "a1b2c3..." }
```

### Request body fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `callbackUrl` | `string` | Yes | HTTPS URL to POST the result to once the job completes |
| `callbackSecret` | `string` | No | Min 32 chars. When present, Boomerang adds `X-Signature-SHA256` to the callback |
| `idempotencyKey` | `string` | No | Max 128 chars. Re-using a key within the cooldown window returns `409 Conflict` |
| `payload` | `object` | No | Arbitrary JSON passed through to `SyncContext#getPayload()` as a `JsonNode` |
| `messageVersion` | `string` | No | Max 64 chars. Schema version of the payload (e.g. `"v1"`). Available in `SyncContext#getMessageVersion()` so handlers can adapt to schema changes mid-queue |

Your callback receives a POST when the job completes:

```json
{
  "jobId": "a1b2c3...",
  "status": "DONE",
  "result": {
    "reportUrl": "https://storage.example.com/reports/monthly-revenue-2026-02.pdf",
    "generatedBy": "user@example.com"
  },
  "completedAt": "2026-03-22T10:15:30Z"
}
```

---

## Features

| Feature | Details |
|---------|---------|
| **JWT auth** | HS256 Bearer token required on all endpoints |
| **Idempotency** | Same caller can't enqueue twice within the cooldown window — `409 Conflict` |
| **Webhook retries** | Exponential backoff, configurable attempts and intervals |
| **HMAC signing** | Optional `X-Signature-SHA256` on every callback |
| **SSRF protection** | Callback URLs validated against an allowlist; RFC-1918 ranges blocked |
| **Dead-letter store** | Failed webhooks stored in Redis with replay and delete endpoints |
| **Status polling** | `GET /{base-path}/{jobId}` — only visible to the job's creator |
| **Metrics** | Micrometer counters and timers for jobs, webhooks, queue depth |

---

## API

All paths are relative to `boomerang.base-path` (default `/jobs`). All endpoints require `Authorization: Bearer <jwt>`.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/{base-path}` | Enqueue a job — returns `202 { jobId }` |
| `GET` | `/{base-path}/{jobId}` | Poll job status (`PENDING`, `IN_PROGRESS`, `DONE`, `FAILED`) |
| `GET` | `/{base-path}/failed-webhooks` | List all dead-lettered webhook deliveries |
| `POST` | `/{base-path}/failed-webhooks/{jobId}/replay` | Re-attempt a failed delivery |
| `DELETE` | `/{base-path}/failed-webhooks/{jobId}` | Discard a failed delivery |

---

## Configuration

```yaml
boomerang:
  base-path: /jobs               # URL prefix for all endpoints — rename to match your domain

  auth:
    jwt-secret: ""               # required; min 32 chars

  callback:
    allowed-domains: []          # e.g. ["example.com"]
    skip-validation: false       # bypass SSRF checks in dev/test

  idempotency:
    cooldown-seconds: 300

  webhook:
    max-attempts: 5
    initial-backoff-ms: 1000
    max-backoff-ms: 30000

  thread-pool:
    core-size: 5
    max-size: 20
    queue-capacity: 100

  job-ttl-days: 7
  failed-webhook-ttl-days: 30
```

---

## Running the sample app

```bash
mvn package -pl boomerang-sample -am -DskipTests
cd boomerang-sample && docker compose up
```

Boomerang doesn't issue JWTs — bring your own. Generate one with [jwt-cli](https://github.com/mike-engel/jwt-cli):

```bash
JWT=$(jwt encode --secret "boomerang-dev-secret-key-min-32-chars!!" --sub demo)

curl -X POST http://localhost:8080/jobs \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"callbackUrl":"https://webhook.site/your-unique-url"}'
```

---

## Integration testing

Add `boomerang-tests` as a test dependency to get a base class with a containerised Redis, WireMock, and JWT helpers:

```xml
<dependency>
    <groupId>io.github.sameerchereddy</groupId>
    <artifactId>boomerang-tests</artifactId>
    <version>VERSION</version>
    <scope>test</scope>
</dependency>
```

```java
@SpringBootTest(webEnvironment = RANDOM_PORT, classes = MyApp.class)
class MySyncTest extends BoomerangIntegrationTestBase {

    @Test
    void callbackDelivered() {
        stubCallbackUrl("/hooks/done");
        // trigger, then...
        Awaitility.await().untilAsserted(() -> verifyCallbackReceived("/hooks/done"));
    }
}
```

See [`boomerang-tests/README.md`](boomerang-tests/README.md) for full details.

---

## SDKs

### Node.js — `@sameerchereddy/boomerang-client`

[![npm](https://img.shields.io/npm/v/@sameerchereddy/boomerang-client)](https://www.npmjs.com/package/@sameerchereddy/boomerang-client)

```bash
npm install @sameerchereddy/boomerang-client
```

```typescript
import { BoomerangClient } from '@sameerchereddy/boomerang-client';

const client = new BoomerangClient({ baseUrl: 'http://localhost:8080', token: '<jwt>' });
const { jobId } = await client.trigger({ callbackUrl: 'https://example.com/hooks/done' });
```

See [`boomerang-node/README.md`](boomerang-node/README.md) for full documentation including webhook middleware for Express and Fastify.

---

## Modules

| Module | Purpose |
|--------|---------|
| [`boomerang-core`](boomerang-core) | Shared model and annotation |
| [`boomerang-redis`](boomerang-redis) | Redis-backed job store, queue, and webhook store |
| [`boomerang-starter`](boomerang-starter) | Auto-configuration, controller, service layer |
| [`boomerang-sample`](boomerang-sample) | Runnable sample application |
| [`boomerang-tests`](boomerang-tests) | Integration test base class for consumers |
| [`boomerang-node`](boomerang-node) | Node.js SDK — `@sameerchereddy/boomerang-client` |

---

## Releasing

Tag a commit — the release pipeline handles the rest:

```bash
git tag vX.Y.Z && git push origin vX.Y.Z
```

Required GitHub secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_TOKEN`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`.

---

## License

Apache 2.0
