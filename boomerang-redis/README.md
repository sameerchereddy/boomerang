# boomerang-redis

Redis persistence layer for Boomerang. Owns all read/write operations against Redis — job metadata, the work queue, idempotency locks, and the failed-webhook dead-letter store.

## Stores

### `BoomerangJobStore`

Stores job metadata (status, caller, timestamps) as Redis hashes with a configurable TTL (`boomerang.job-ttl-days`). Keyed by `boomerang:job:{jobId}`.

### `BoomerangQueueStore`

A Redis list used as a FIFO work queue. The starter's worker threads `BLPOP` from this list. Keyed by `boomerang:queue`.

### `BoomerangIdempotencyStore`

Stores per-caller locks using `SET NX EX` (atomic set-if-absent with expiry). Prevents the same caller from enqueuing a second job within the cooldown window (`boomerang.idempotency.cooldown-seconds`). Key pattern: `boomerang:idem:{callerId}`.

### `BoomerangFailedWebhookStore`

Dead-letter store for webhook deliveries that exhausted all retry attempts. Stored as Redis hashes with a configurable TTL (`boomerang.failed-webhook-ttl-days`). Supports listing, replay, and deletion. Key pattern: `boomerang:failed:{jobId}`.

## Dependencies

- `spring-boot-starter-data-redis`
- `boomerang-core`

## Notes

All keys are prefixed with `boomerang:` so they can coexist safely in a shared Redis instance. TTLs are set on write — no background cleanup jobs needed.
