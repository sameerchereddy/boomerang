import type { Request, Response, NextFunction, RequestHandler } from 'express';
import { BoomerangSignature } from '../signature.js';
import type { BoomerangPayload } from '../types.js';

declare global {
  namespace Express {
    interface Request {
      boomerangPayload?: BoomerangPayload;
    }
  }
}

/**
 * Express middleware for verifying Boomerang webhook signatures.
 *
 * Requirements:
 * - Must be mounted AFTER `express.raw({ type: 'application/json' })` so that
 *   `req.body` is a raw Buffer. If your app uses `express.json()` globally,
 *   apply `express.raw({ type: 'application/json' })` to the specific route
 *   before this middleware.
 *
 * Usage:
 *   app.post('/hooks', express.raw({ type: 'application/json' }), boomerangWebhook(secret), handler)
 */
export function boomerangWebhook(secret: string): RequestHandler {
  return (req: Request, res: Response, next: NextFunction): void => {
    const signature = req.headers['x-signature-sha256'] as string | undefined;

    if (!signature) {
      res.status(401).json({ error: 'Missing X-Signature-SHA256 header' });
      return;
    }

    const body = req.body as Buffer;
    if (!Buffer.isBuffer(body)) {
      res.status(400).json({ error: 'Raw body required — use express.raw({ type: "application/json" }) before this middleware' });
      return;
    }

    if (!BoomerangSignature.verify(body, signature, secret)) {
      res.status(401).json({ error: 'Invalid signature' });
      return;
    }

    try {
      req.boomerangPayload = JSON.parse(body.toString('utf8')) as BoomerangPayload;
    } catch {
      res.status(400).json({ error: 'Invalid JSON body' });
      return;
    }

    next();
  };
}
