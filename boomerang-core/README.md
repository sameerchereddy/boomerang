# boomerang-core

Shared model and annotation module. Everything else in the Boomerang stack depends on this; nothing here depends on them.

## Contents

### `@BoomerangHandler`

Method-level annotation that marks the single async handler in a Boomerang application.

```java
@BoomerangHandler
public Map<String, Object> run(SyncContext ctx) { ... }
```

Exactly one method across the entire application context must carry this annotation. The auto-configuration validates this at startup and throws if zero or more than one are found.

### `SyncContext`

Read-only view of a job passed into the handler at execution time.

| Method | Type | Description |
|--------|------|-------------|
| `getJobId()` | `String` | Unique job identifier (UUID) |
| `getCallerId()` | `String` | JWT `sub` claim of the caller who enqueued the job |
| `getPayload()` | `Map<String, Object>` | Arbitrary JSON from the request body's `payload` field |

### `JobStatus`

Enum used across the job lifecycle: `PENDING` → `IN_PROGRESS` → `DONE` / `FAILED`.

## Dependencies

None beyond the JDK. No Spring, no Redis, no Jackson.
