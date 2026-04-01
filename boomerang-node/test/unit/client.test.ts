import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { BoomerangClient } from '../../src/client.js';
import {
  BoomerangError,
  BoomerangUnauthorizedError,
  BoomerangForbiddenError,
  BoomerangConflictError,
  BoomerangServiceUnavailableError,
} from '../../src/errors.js';

const BASE_URL = 'http://localhost:8080';
const TOKEN = 'test-token';

function mockFetch(status: number, body: unknown): void {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    statusText: 'Status ' + status,
    json: () => Promise.resolve(body),
  }));
}

describe('BoomerangClient.trigger', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('returns jobId on 200', async () => {
    mockFetch(200, { jobId: 'job-123' });
    const client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
    const result = await client.trigger({ callbackUrl: 'https://example.com/hook' });
    expect(result.jobId).toBe('job-123');
  });

  it('sends Authorization header', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ jobId: 'x' }),
    });
    vi.stubGlobal('fetch', fetchMock);
    const client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
    await client.trigger({ callbackUrl: 'https://example.com/hook' });
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)['Authorization']).toBe(`Bearer ${TOKEN}`);
  });

  it('throws BoomerangUnauthorizedError on 401', async () => {
    mockFetch(401, { error: 'Unauthorized' });
    const client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
    await expect(client.trigger({ callbackUrl: 'https://example.com/hook' }))
      .rejects.toBeInstanceOf(BoomerangUnauthorizedError);
  });

  it('throws BoomerangForbiddenError on 403', async () => {
    mockFetch(403, { error: 'Forbidden' });
    const client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
    await expect(client.trigger({ callbackUrl: 'https://example.com/hook' }))
      .rejects.toBeInstanceOf(BoomerangForbiddenError);
  });

  it('throws BoomerangConflictError on 409 with retryAfterSeconds', async () => {
    mockFetch(409, { error: 'Conflict', retryAfterSeconds: 60 });
    const client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
    const err = await client.trigger({ callbackUrl: 'https://example.com/hook' }).catch(e => e);
    expect(err).toBeInstanceOf(BoomerangConflictError);
    expect((err as BoomerangConflictError).retryAfterSeconds).toBe(60);
  });

  it('throws BoomerangServiceUnavailableError on 503', async () => {
    mockFetch(503, { error: 'Service unavailable' });
    const client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
    await expect(client.trigger({ callbackUrl: 'https://example.com/hook' }))
      .rejects.toBeInstanceOf(BoomerangServiceUnavailableError);
  });

  it('throws BoomerangError for unknown status codes', async () => {
    mockFetch(500, { error: 'Internal error' });
    const client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
    const err = await client.trigger({ callbackUrl: 'https://example.com/hook' }).catch(e => e);
    expect(err).toBeInstanceOf(BoomerangError);
    expect((err as BoomerangError).statusCode).toBe(500);
  });
});

describe('BoomerangClient.poll', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('returns job status on 200', async () => {
    mockFetch(200, {
      jobId: 'job-123',
      status: 'DONE',
      createdAt: '2024-01-01T00:00:00Z',
      completedAt: '2024-01-01T00:01:00Z',
    });
    const client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
    const result = await client.poll('job-123');
    expect(result.jobId).toBe('job-123');
    expect(result.status).toBe('DONE');
  });

  it('URL-encodes the jobId', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ jobId: 'job/with/slashes', status: 'PENDING', createdAt: '', completedAt: null }),
    });
    vi.stubGlobal('fetch', fetchMock);
    const client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
    await client.poll('job/with/slashes');
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe(`${BASE_URL}/sync/job%2Fwith%2Fslashes`);
  });

  it('throws BoomerangUnauthorizedError on 401', async () => {
    mockFetch(401, { error: 'Unauthorized' });
    const client = new BoomerangClient({ baseUrl: BASE_URL, token: TOKEN });
    await expect(client.poll('job-123')).rejects.toBeInstanceOf(BoomerangUnauthorizedError);
  });
});
