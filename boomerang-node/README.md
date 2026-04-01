# @sameerchereddy/boomerang-client

[![npm](https://img.shields.io/npm/v/@sameerchereddy/boomerang-client)](https://www.npmjs.com/package/@sameerchereddy/boomerang-client)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](../LICENSE)

Thin Node.js SDK for [boomerang-standalone](../boomerang-standalone). Handles authentication, typed request/response models, HMAC signature verification, and Express/Fastify webhook middleware.

No queue logic. No Redis. All of that lives in the standalone service.

---

## Requirements

- Node.js >= 18

---

## Installation

```bash
npm install @sameerchereddy/boomerang-client
```

---

## Quick start

```typescript
import { BoomerangClient } from '@sameerchereddy/boomerang-client';

const client = new BoomerangClient({
  baseUrl: 'http://localhost:8080',
  token: '<your-jwt>',
});

// Trigger a job
const { jobId } = await client.trigger({
  workerUrl:   'https://myapp.example.com/internal/do-work',
  callbackUrl: 'https://myapp.example.com/hooks/done',
});

// Poll job status
const status = await client.poll(jobId);
console.log(status.status); // 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'FAILED'
```

---

## API

### `BoomerangClient`

#### `trigger(request)`

Enqueues a job. Returns a `jobId`.

```typescript
const { jobId } = await client.trigger({
  workerUrl:      'https://myapp.example.com/internal/do-work',  // optional
  callbackUrl:    'https://myapp.example.com/hooks/done',        // required
  callbackSecret: 'min-32-char-secret-for-hmac-signing!!',       // optional
  idempotencyKey: 'unique-key-to-prevent-duplicates',            // optional
  payload:        { userId: '42', action: 'process' },           // optional
  messageVersion: 'v1',                                          // optional
});
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `callbackUrl` | `string` | Yes | URL to POST the result to when the job completes |
| `workerUrl` | `string` | No | URL Boomerang calls to execute the work |
| `callbackSecret` | `string` | No | Min 32 chars. Enables `X-Signature-SHA256` on the callback |
| `idempotencyKey` | `string` | No | Max 128 chars. Reusing within the cooldown window returns `409` |
| `payload` | `object` | No | Arbitrary JSON passed through to your worker |
| `messageVersion` | `string` | No | Schema version of the payload (e.g. `"v1"`) |

#### `poll(jobId)`

Returns the current status of a job.

```typescript
const status = await client.poll(jobId);
// {
//   jobId: '...',
//   status: 'DONE',
//   createdAt: '2026-01-01T00:00:00Z',
//   completedAt: '2026-01-01T00:01:00Z'
// }
```

---

## Webhook middleware

Boomerang sends a signed POST to your `callbackUrl` when a job completes. Use the middleware to verify the signature and parse the payload.

### Express

```bash
npm install express @sameerchereddy/boomerang-client
```

```typescript
import express from 'express';
import { boomerangWebhook } from '@sameerchereddy/boomerang-client/express';

const app = express();

app.post(
  '/hooks/done',
  express.raw({ type: 'application/json' }),  // must come before boomerangWebhook
  boomerangWebhook('your-callback-secret-min-32-chars!!'),
  (req, res) => {
    const payload = req.boomerangPayload;
    // payload.jobId, payload.status, payload.result, payload.error
    res.sendStatus(200);
  },
);
```

> `express.raw({ type: 'application/json' })` must be applied before `boomerangWebhook` so the raw body buffer is available for signature verification. If you use `express.json()` globally, apply `express.raw` at the route level as shown above.

### Fastify

```bash
npm install fastify fastify-plugin @sameerchereddy/boomerang-client
```

```typescript
import Fastify from 'fastify';
import { boomerangPlugin } from '@sameerchereddy/boomerang-client/fastify';

const fastify = Fastify();

await fastify.register(boomerangPlugin, {
  secret: 'your-callback-secret-min-32-chars!!',
});

fastify.post('/hooks/done', async (request, reply) => {
  const payload = request.boomerangPayload;
  // payload.jobId, payload.status, payload.result, payload.error
  reply.send({ ok: true });
});
```

### Callback payload shape

```typescript
{
  jobId:       string;
  status:      'DONE' | 'FAILED';
  completedAt: string;        // ISO 8601
  result?:     unknown;       // present on DONE
  error?:      string;        // present on FAILED
}
```

---

## Error handling

All API errors throw typed subclasses of `BoomerangError`:

```typescript
import {
  BoomerangError,
  BoomerangUnauthorizedError,
  BoomerangForbiddenError,
  BoomerangConflictError,
  BoomerangServiceUnavailableError,
} from '@sameerchereddy/boomerang-client';

try {
  await client.trigger({ callbackUrl: 'https://example.com/hooks' });
} catch (err) {
  if (err instanceof BoomerangConflictError) {
    // duplicate idempotency key
    console.log(err.retryAfterSeconds); // seconds to wait before retrying
  } else if (err instanceof BoomerangUnauthorizedError) {
    // invalid or missing JWT
  } else if (err instanceof BoomerangError) {
    console.log(err.statusCode, err.message);
  }
}
```

| Class | Status | Extra field |
|-------|--------|-------------|
| `BoomerangUnauthorizedError` | 401 | — |
| `BoomerangForbiddenError` | 403 | — |
| `BoomerangConflictError` | 409 | `retryAfterSeconds` |
| `BoomerangServiceUnavailableError` | 503 | — |
| `BoomerangError` | any | `statusCode` |

---

## Signature verification (manual)

If you're not using the Express or Fastify middleware, you can verify signatures directly:

```typescript
import { BoomerangSignature } from '@sameerchereddy/boomerang-client';

// In your webhook handler — body must be the raw Buffer, not parsed JSON
const isValid = BoomerangSignature.verify(
  rawBodyBuffer,
  req.headers['x-signature-sha256'],
  'your-callback-secret-min-32-chars!!',
);

if (!isValid) {
  return res.status(401).send('Invalid signature');
}
```

Signatures use HMAC-SHA256 and are formatted as `sha256=<lowercase-hex>`.

---

## Releasing

Tag a commit — the publish pipeline handles the rest:

```bash
git tag node-vX.Y.Z && git push origin node-vX.Y.Z
```

Required GitHub secret: `NPM_TOKEN`.
