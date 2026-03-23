# boomerang-starter

Spring Boot auto-configuration module. Pulls together the core, Redis, and the application layer into a single dependency that consumers drop into their project.

## What gets auto-configured

When `@EnableBoomerang` is present on the application class, the starter wires up:

- **`BoomerangController`** — REST endpoints (`POST /jobs`, `GET /jobs/{jobId}`, failed-webhook endpoints) — path prefix configurable via `boomerang.base-path`
- **`BoomerangWorker`** — background thread pool that dequeues jobs and invokes the `@BoomerangHandler`
- **`BoomerangWebhookService`** — delivers results to callback URLs with exponential-backoff retries
- **`JwtAuthFilter`** — validates HS256 Bearer tokens on every request
- **`CallbackUrlValidator`** — SSRF protection; rejects private/loopback addresses and URLs outside the configured allowlist

## Configuration

All properties are under the `boomerang.*` prefix. See the [root README](../README.md#configuration) for the full reference.

Key properties:

```yaml
boomerang:
  auth:
    jwt-secret: ""            # required

  callback:
    allowed-domains: []       # SSRF allowlist

  webhook:
    max-attempts: 5
    initial-backoff-ms: 1000
    max-backoff-ms: 30000

  thread-pool:
    core-size: 5
    max-size: 20
    queue-capacity: 100
```

## Handler discovery

At startup, `BoomerangAutoConfiguration` scans all beans for exactly one method annotated with `@BoomerangHandler`. The method must accept a single `SyncContext` parameter and return a value serialisable to JSON. The app fails fast with a clear message if the constraint is violated.

## Dependencies

- `boomerang-core`
- `boomerang-redis`
- `spring-boot-starter-web`
- `spring-boot-starter-data-redis`
- `spring-retry`
- `jjwt`
- `micrometer-core`
