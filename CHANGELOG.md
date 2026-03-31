# Changelog

All notable changes to Boomerang are documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versions follow [Semantic Versioning](https://semver.org/).

---

## [4.0.0] - 2026-03-30

### Added
- **Standalone mode** — jobs can now carry a `workerUrl` field. When present, Boomerang
  calls the consumer's HTTP endpoint instead of a local `@BoomerangHandler`, captures the
  response body as the job result, and fires the webhook as normal. Enables thin SDK
  integrations from any language.
- **`BoomerangRequest.workerUrl`** — optional HTTPS URL of the consumer's worker endpoint.
  Subject to the same SSRF allowlist as `callbackUrl`.
- **`boomerang-standalone` module** — runnable fat-jar service for standalone deployments.
  Consumers point their `workerUrl` at this service rather than embedding Boomerang.
  Distributed as a Docker image (`ghcr.io/sameerchereddy/boomerang`).
- **`Dockerfile`** — non-root Alpine image for production use.
- **`docker-compose.yml`** — local development stack (Boomerang + Redis).
- **GitHub Actions workflow** (`.github/workflows/docker-publish.yml`) — builds and pushes
  the Docker image to GHCR on every `v*` tag.
- **`boomerang.worker.*` config group** — configures worker invocation behaviour:
  - `max-attempts` (default `3`) — retry attempts before marking job `FAILED`
  - `timeout-seconds` (default `300`) — per-attempt HTTP response timeout
  - `max-response-size-bytes` (default `10485760` / 10 MB) — response body size limit
- **`boomerang.worker.invocations` metric** — counts standalone-mode dispatches.
- **`boomerang.worker.invocation.failures` metric** — counts exhausted worker retries.
- **`BoomerangIntegrationTestBase` worker helpers** — `stubWorkerUrl`, `stubWorkerUrlWithFailure`,
  `verifyWorkerCalledWithJobId`, `verifyWorkerSignaturePresent` for standalone mode tests.
- **`BoomerangStandaloneModeIT`** — 12-scenario compliance test suite for standalone mode.

### Changed
- `@BoomerangHandler` is no longer required at startup. Applications with no handler log
  an info message and boot cleanly, operating in standalone-only mode. Jobs without a
  `workerUrl` and without a registered handler will fail at processing time with a clear
  error message.
- `BoomerangWebhookService` HMAC logic extracted to package-private `BoomerangHmacUtils`
  and reused by `StandaloneWorkerInvoker` — no behaviour change.
- `BoomerangWorker` accepts `StandaloneWorkerInvoker` as a constructor dependency
  (injected automatically via auto-configuration — no consumer changes required).
- Dedicated `boomerangWorkerRestClient` bean with a configurable response timeout separate
  from the webhook client.

---

## [3.0.0] - 2026-03-25

### Added
- **Poisoned-well protection** — new optional `messageVersion` field on `BoomerangRequest`
  (e.g. `"v1"`, `"v2"`). Stored with the job and surfaced in `SyncContext#getMessageVersion()`
  so handlers can detect and adapt to payload schema changes mid-queue. When an application is
  deployed while older jobs are still queued, the handler can inspect the version and branch
  accordingly rather than silently misinterpreting a stale payload.

### Changed
- `SyncContext` gains a `messageVersion` field (`String`, nullable).
- `BoomerangRequest` gains a `messageVersion` field (`String`, max 64 chars, nullable).
- `BoomerangJobRecord` gains a `messageVersion` field persisted in Redis.

---

---

## [2.0.0] - 2026-03-25

### Added
- **`JobId` value object** (`io.github.boomerang.model.JobId`) — replaces the raw `String` in
  `SyncContext`. Annotated with `@JsonValue` so it serialises as a plain string. Prevents
  accidentally mixing job IDs with other string-typed values at compile time.
- **Generic JSON payload** — callers can now include an arbitrary `payload` object in the
  `POST /jobs` request body. It is stored alongside the job and delivered to the handler via
  `SyncContext#getPayload()` as a `JsonNode`, ready to read or deserialise to a typed class.

### Changed
- `SyncContext` now carries `JobId jobId` (was `String jobId`) and the new `JsonNode payload`
  field. Handlers that previously referenced `ctx.getJobId()` as a `String` should call
  `ctx.getJobId().toString()` or `ctx.getJobId().value()` where a plain string is needed.
- `BoomerangRequest` gains a `payload` field (`JsonNode`, nullable).
- `BoomerangJobRecord` gains a `payload` field (`String` JSON, nullable) persisted in Redis.
- `BoomerangWorker` now accepts an `ObjectMapper` in its constructor (injected automatically
  via `boomerangObjectMapper` — no consumer changes required if using the auto-configuration).
- `boomerang-core` now declares `jackson-databind` as a compile dependency.

---

## [1.0.0] - 2026-03-01

### Added
- Initial release of Boomerang Spring Boot Starter.
- `POST /jobs` endpoint — accepts a job and returns `202 Accepted` with a `jobId` in <50 ms.
- Redis-backed job queue and status store (`boomerang-redis`).
- `@BoomerangHandler` annotation — marks exactly one method as the async job handler.
- `SyncContext` — immutable context passed to the handler with `jobId`, `callerId`, and
  `triggeredAt`.
- Webhook delivery with exponential-backoff retries and dead-letter storage.
- HMAC-SHA256 callback signing via `callbackSecret`.
- Idempotency via `idempotencyKey` with configurable cooldown window.
- JWT Bearer authentication (`boomerang.auth.jwt-secret`).
- SSRF protection via callback URL allowlist (`boomerang.callback.allowed-domains`).
- Job status polling — `GET /jobs/{jobId}` (ownership-enforced).
- Failed-webhook management — list, replay, and delete dead-lettered deliveries.
- Micrometer metrics: job counters/timers, webhook counters/timers, queue depth gauge.
- `boomerang-tests` — Testcontainers + WireMock base class for integration testing.
- `boomerang-sample` — runnable example Spring Boot application.
