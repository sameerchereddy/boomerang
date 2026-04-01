import { describe, it, expect, beforeAll } from 'vitest';
import { BoomerangClient } from '../../src/client.js';
import {
  BoomerangConflictError,
  BoomerangUnauthorizedError,
} from '../../src/errors.js';

const BASE_URL = process.env.BOOMERANG_URL ?? 'http://localhost:8080';
const TOKEN = process.env.BOOMERANG_TOKEN ?? 'integration-test-token';
const WIREMOCK_URL = process.env.WIREMOCK_URL ?? 'http://localhost:8888';

let client: BoomerangClient;

beforeAll(() => {
  client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
});

describe('POST /sync — trigger', () => {
  it('returns a jobId for a valid request', async () => {
    const res = await client.trigger({ callbackUrl: `${WIREMOCK_URL}/callback` });
    expect(res.jobId).toBeTruthy();
    expect(typeof res.jobId).toBe('string');
  });

  it('returns a jobId when workerUrl is provided', async () => {
    const res = await client.trigger({
      callbackUrl: `${WIREMOCK_URL}/callback`,
      workerUrl: `${WIREMOCK_URL}/worker`,
    });
    expect(res.jobId).toBeTruthy();
  });

  it('accepts a payload passthrough', async () => {
    const res = await client.trigger({
      callbackUrl: `${WIREMOCK_URL}/callback`,
      payload: { userId: '42', action: 'process' },
    });
    expect(res.jobId).toBeTruthy();
  });

  it('returns 409 for duplicate idempotencyKey', async () => {
    const key = `idem-${Date.now()}`;
    await client.trigger({ callbackUrl: `${WIREMOCK_URL}/callback`, idempotencyKey: key });
    const err = await client
      .trigger({ callbackUrl: `${WIREMOCK_URL}/callback`, idempotencyKey: key })
      .catch(e => e);
    expect(err).toBeInstanceOf(BoomerangConflictError);
  });

  it('returns 401 for missing token', async () => {
    const unauthClient = new BoomerangClient({ baseUrl: BASE_URL, token: '' });
    await expect(
      unauthClient.trigger({ callbackUrl: `${WIREMOCK_URL}/callback` }),
    ).rejects.toBeInstanceOf(BoomerangUnauthorizedError);
  });

  it('returns 401 for invalid token', async () => {
    const unauthClient = new BoomerangClient({ baseUrl: BASE_URL, token: 'bad-token' });
    await expect(
      unauthClient.trigger({ callbackUrl: `${WIREMOCK_URL}/callback` }),
    ).rejects.toBeInstanceOf(BoomerangUnauthorizedError);
  });
});
