# Boomerang

[![Maven Central](https://img.shields.io/maven-central/v/io.github.sameerchereddy/boomerang-starter?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.sameerchereddy/boomerang-starter)
[![npm](https://img.shields.io/npm/v/@sameerchereddy/boomerang-client?label=npm)](https://www.npmjs.com/package/@sameerchereddy/boomerang-client)
[![Go Reference](https://pkg.go.dev/badge/github.com/sameerchereddy/boomerang-go@v1.0.1.svg)](https://pkg.go.dev/github.com/sameerchereddy/boomerang-go@v1.0.1)
[![PyPI](https://img.shields.io/pypi/v/boomerang-python?label=PyPI)](https://pypi.org/project/boomerang-python/)
[![NuGet](https://img.shields.io/nuget/v/Boomerang.Client?label=NuGet)](https://www.nuget.org/packages/Boomerang.Client)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

Like a boomerang — you throw something out and it comes back to you.

You POST a request. Boomerang returns a `202` in under 50 ms. Your handler runs in the background. When it's done, the result flies back to whatever URL you gave it. That's it.

No polling loops. No managing background threads. No reinventing the webhook wheel.

---

## How it works

```
Client → POST /jobs { callbackUrl }
           ↓ 202 + jobId  (< 50 ms)
       [background]
           ↓ your handler runs
           ↓ POST callbackUrl { jobId, status, result }
```

The server is a standalone Spring Boot service (or embeddable starter) backed by Redis. Thin SDKs in every language handle the HTTP calls — no queue logic, no Redis dependency on the client side.

---

## SDKs

Pick the SDK for your stack. All of them speak the same wire format and honour the same contract.

### Java — Spring Boot Starter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.sameerchereddy/boomerang-starter?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.sameerchereddy/boomerang-starter)

```xml
<dependency>
    <groupId>io.github.sameerchereddy</groupId>
    <artifactId>boomerang-starter</artifactId>
    <version>VERSION</version>
</dependency>
```

```java
@SpringBootApplication
@EnableBoomerang
public class MyApp { ... }

@Component
public class MyHandler {
    @BoomerangHandler
    public Map<String, Object> handle(SyncContext ctx) {
        // do the work, return the result
        return Map.of("url", generate(ctx.getPayload()));
    }
}
```

See [`boomerang-starter`](boomerang-starter) for full configuration reference.

---

### Node.js — `@sameerchereddy/boomerang-client`

[![npm](https://img.shields.io/npm/v/@sameerchereddy/boomerang-client)](https://www.npmjs.com/package/@sameerchereddy/boomerang-client)

```bash
npm install @sameerchereddy/boomerang-client
```

```typescript
import { BoomerangClient } from '@sameerchereddy/boomerang-client';

const client = new BoomerangClient({ baseUrl: 'https://boomerang.your-org.com', token: '<jwt>' });
const { jobId } = await client.trigger({ callbackUrl: 'https://yourapp.com/hooks/done' });
const status = await client.poll(jobId);
```

See [`boomerang-node/README.md`](boomerang-node/README.md) for webhook middleware for Express and Fastify.

---

### Go — `boomerang-go`

[![Go Reference](https://pkg.go.dev/badge/github.com/sameerchereddy/boomerang-go@v1.0.1.svg)](https://pkg.go.dev/github.com/sameerchereddy/boomerang-go@v1.0.1)

```bash
go get github.com/sameerchereddy/boomerang-go@v1.0.1
```

```go
client := boomerang.NewClient("https://boomerang.your-org.com", "<jwt>")
resp, _ := client.Trigger(ctx, &boomerang.TriggerRequest{CallbackUrl: "https://yourapp.com/hooks/done"})
status, _ := client.Poll(ctx, resp.JobId)
```

See [`boomerang-go/README.md`](boomerang-go/README.md) for full documentation.

---

### Python — `boomerang-python`

[![PyPI](https://img.shields.io/pypi/v/boomerang-python)](https://pypi.org/project/boomerang-python/)

Built by [@sudheerr48](https://github.com/sudheerr48).

```bash
pip install boomerang-python
```

```python
from boomerang import BoomerangClient

client = BoomerangClient(base_url="https://boomerang.your-org.com", token="<jwt>")
job = client.trigger(callback_url="https://yourapp.com/hooks/done")
status = client.poll(job.job_id)
```

See [`boomerang-python/README.md`](boomerang-python/README.md) for full documentation.

---

### C# / .NET — `Boomerang.Client`

[![NuGet](https://img.shields.io/nuget/v/Boomerang.Client?label=NuGet)](https://www.nuget.org/packages/Boomerang.Client)

Built by [@agoginen](https://github.com/agoginen).

```bash
dotnet add package Boomerang.Client
dotnet add package Boomerang.Client.AspNetCore  # optional — webhook filter for ASP.NET Core
```

```csharp
var client = new BoomerangClient(new BoomerangClientOptions
{
    BaseUrl = new Uri("https://boomerang.your-org.com/"),
    Token   = Environment.GetEnvironmentVariable("BOOMERANG_JWT")!,
});

var job = await client.TriggerAsync(new BoomerangTriggerRequest
{
    CallbackUrl    = "https://yourapp.com/hooks/done",
    CallbackSecret = Environment.GetEnvironmentVariable("WEBHOOK_SECRET"),
});

var status = await client.PollAsync(job.JobId);
```

```csharp
// ASP.NET Core — signature verified automatically before the action runs
[HttpPost("/hooks/done")]
[BoomerangWebhook(SecretEnvironmentVariable = "WEBHOOK_SECRET")]
public IActionResult OnDone([FromBody] BoomerangWebhookPayload payload) => Ok();
```

See [`boomerang-dotnet/README.md`](boomerang-dotnet/README.md) for full documentation.

---

## API reference

All endpoints require `Authorization: Bearer <jwt>` (HS256). Paths are relative to `boomerang.base-path` (default `/sync`).

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/{base-path}` | Enqueue a job — returns `202 { jobId }` |
| `GET` | `/{base-path}/{jobId}` | Poll job status (`PENDING`, `IN_PROGRESS`, `DONE`, `FAILED`) |
| `GET` | `/{base-path}/failed-webhooks` | List dead-lettered webhook deliveries |
| `POST` | `/{base-path}/failed-webhooks/{jobId}/replay` | Re-attempt a failed delivery |
| `DELETE` | `/{base-path}/failed-webhooks/{jobId}` | Discard a failed delivery |

### Request body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `callbackUrl` | string | Yes | URL to POST the result to when the job completes |
| `callbackSecret` | string | No | Min 32 chars. Enables `X-Signature-SHA256` on callbacks |
| `idempotencyKey` | string | No | Max 128 chars. Duplicate within cooldown window returns `409` |
| `payload` | object | No | Arbitrary JSON passed to your handler |
| `messageVersion` | string | No | Schema version string (e.g. `"v1"`) for payload evolution |
| `workerUrl` | string | Standalone only | HTTP URL Boomerang POSTs to instead of an in-process handler |

### Webhook callback payload

```json
{
  "boomerangVersion": "1",
  "jobId": "a1b2c3...",
  "status": "DONE",
  "result": { ... },
  "completedAt": "2026-03-22T10:15:30Z",
  "error": null
}
```

### HMAC signature verification

When `callbackSecret` is set, every callback includes `X-Signature-SHA256: sha256=<lowercase hex>` — HMAC-SHA256 over the raw request body. All SDKs include a helper to verify this in constant time.

---

## Running the standalone server

```bash
docker compose -f boomerang-standalone/docker-compose.yml up
```

The server starts on port `8080`. Set `BOOMERANG_JWT_SECRET` (min 32 chars) and `SPRING_DATA_REDIS_HOST` in the compose file or via environment variables.

Boomerang does not issue JWTs — generate one with [jwt-cli](https://github.com/mike-engel/jwt-cli):

```bash
JWT=$(jwt encode --secret "your-secret-min-32-chars!!" --sub myapp)

curl -X POST http://localhost:8080/sync \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"callbackUrl":"https://yourapp.com/hooks/done","workerUrl":"https://yourapp.com/internal/work"}'
```

---

## License

Apache 2.0
