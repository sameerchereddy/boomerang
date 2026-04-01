import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import express from 'express';
import type { Server } from 'node:http';
import { boomerangWebhook } from '../../src/middleware/express.js';
import { BoomerangSignature } from '../../src/signature.js';
import type { BoomerangPayload } from '../../src/types.js';

const SECRET = 'test-callback-secret-that-is-32c';
const PORT = 19_876;
const WEBHOOK_URL = `http://localhost:${PORT}/hook`;

let server: Server;
let lastPayload: BoomerangPayload | undefined;
let lastStatus: number | undefined;

beforeAll(() => {
  return new Promise<void>(resolve => {
    const app = express();
    app.post(
      '/hook',
      express.raw({ type: 'application/json' }),
      boomerangWebhook(SECRET),
      (req, res) => {
        lastPayload = req.boomerangPayload;
        lastStatus = 200;
        res.status(200).json({ ok: true });
      },
    );
    server = app.listen(PORT, resolve);
  });
});

afterAll(() => {
  return new Promise<void>((resolve, reject) => {
    server.close(err => (err ? reject(err) : resolve()));
  });
});

async function postWebhook(payload: unknown, secret = SECRET): Promise<Response> {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  const signature = BoomerangSignature.compute(body, secret);
  return fetch(WEBHOOK_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Signature-SHA256': signature,
    },
    body,
  });
}

describe('Express webhook middleware', () => {
  it('accepts a valid signed payload and attaches boomerangPayload', async () => {
    const payload: BoomerangPayload = {
      jobId: 'job-1',
      status: 'DONE',
      completedAt: '2024-01-01T00:01:00Z',
      result: { answer: 42 },
    };
    const res = await postWebhook(payload);
    expect(res.status).toBe(200);
    expect(lastPayload?.jobId).toBe('job-1');
    expect(lastPayload?.status).toBe('DONE');
  });

  it('returns 401 for missing signature header', async () => {
    const res = await fetch(WEBHOOK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ jobId: 'x', status: 'DONE', completedAt: '' }),
    });
    expect(res.status).toBe(401);
  });

  it('returns 401 for tampered body', async () => {
    const original = Buffer.from(JSON.stringify({ jobId: 'x', status: 'DONE', completedAt: '' }), 'utf8');
    const signature = BoomerangSignature.compute(original, SECRET);
    const tampered = JSON.stringify({ jobId: 'evil', status: 'DONE', completedAt: '' });
    const res = await fetch(WEBHOOK_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Signature-SHA256': signature,
      },
      body: tampered,
    });
    expect(res.status).toBe(401);
  });

  it('returns 401 for wrong secret', async () => {
    const res = await postWebhook({ jobId: 'x', status: 'DONE', completedAt: '' }, 'wrong-secret-that-is-32-chars!!!');
    expect(res.status).toBe(401);
  });

  it('handles FAILED status payload', async () => {
    const payload: BoomerangPayload = {
      jobId: 'job-fail',
      status: 'FAILED',
      completedAt: '2024-01-01T00:01:00Z',
      error: 'worker timed out',
    };
    const res = await postWebhook(payload);
    expect(res.status).toBe(200);
    expect(lastPayload?.status).toBe('FAILED');
    expect(lastPayload?.error).toBe('worker timed out');
  });
});
