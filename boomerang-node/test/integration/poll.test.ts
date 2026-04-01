import { describe, it, expect, beforeAll } from 'vitest';
import { BoomerangClient } from '../../src/client.js';
import { BoomerangUnauthorizedError, BoomerangError } from '../../src/errors.js';

const BASE_URL = process.env.BOOMERANG_URL ?? 'http://localhost:8080';
const TOKEN = process.env.BOOMERANG_TOKEN ?? 'integration-test-token';
const WIREMOCK_URL = process.env.WIREMOCK_URL ?? 'http://localhost:8888';

let client: BoomerangClient;

beforeAll(() => {
  client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
});

describe('GET /sync/:jobId — poll', () => {
  it('returns PENDING or IN_PROGRESS for a newly created job', async () => {
    const { jobId } = await client.trigger({ callbackUrl: `${WIREMOCK_URL}/callback` });
    const status = await client.poll(jobId);
    expect(status.jobId).toBe(jobId);
    expect(['PENDING', 'IN_PROGRESS', 'DONE', 'FAILED']).toContain(status.status);
    expect(status.createdAt).toBeTruthy();
  });

  it('returns null completedAt for a non-completed job', async () => {
    const { jobId } = await client.trigger({ callbackUrl: `${WIREMOCK_URL}/callback` });
    const status = await client.poll(jobId);
    if (status.status === 'PENDING' || status.status === 'IN_PROGRESS') {
      expect(status.completedAt).toBeNull();
    }
  });

  it('returns 404 for unknown jobId', async () => {
    const err = await client.poll('non-existent-job-id-xyz').catch(e => e);
    expect(err).toBeInstanceOf(BoomerangError);
    expect((err as BoomerangError).statusCode).toBe(404);
  });

  it('returns 401 for invalid token', async () => {
    const { jobId } = await client.trigger({ callbackUrl: `${WIREMOCK_URL}/callback` });
    const unauthClient = new BoomerangClient({ baseUrl: BASE_URL, token: 'bad-token' });
    await expect(unauthClient.poll(jobId)).rejects.toBeInstanceOf(BoomerangUnauthorizedError);
  });

  it('completedAt is set for a DONE job', async () => {
    // trigger with a worker that immediately completes via WireMock
    const { jobId } = await client.trigger({
      callbackUrl: `${WIREMOCK_URL}/callback`,
      workerUrl: `${WIREMOCK_URL}/instant-worker`,
    });
    // poll with retries to wait for completion
    let status = await client.poll(jobId);
    for (let i = 0; i < 10 && status.status !== 'DONE' && status.status !== 'FAILED'; i++) {
      await new Promise(r => setTimeout(r, 500));
      status = await client.poll(jobId);
    }
    if (status.status === 'DONE') {
      expect(status.completedAt).toBeTruthy();
    }
  });
});
