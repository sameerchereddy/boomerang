import type { FastifyPluginCallback, FastifyRequest } from 'fastify';
import fp from 'fastify-plugin';
import { BoomerangSignature } from '../signature.js';
import type { BoomerangPayload } from '../types.js';

declare module 'fastify' {
  interface FastifyRequest {
    boomerangPayload?: BoomerangPayload;
  }
}

export interface BoomerangFastifyOptions {
  secret: string;
}

/**
 * Fastify plugin for verifying Boomerang webhook signatures.
 *
 * Registers an `addContentTypeParser` for `application/json` on the route
 * to capture the raw body as a Buffer before signature verification.
 *
 * Usage:
 *   await fastify.register(boomerangPlugin, { secret })
 *   fastify.post('/hooks', { config: { boomerang: true } }, handler)
 *
 * Or apply the hook per-route using onRequest:
 *   fastify.post('/hooks', { onRequest: boomerangHook(secret) }, handler)
 */
const boomerangPluginFn: FastifyPluginCallback<BoomerangFastifyOptions> = (fastify, options, done) => {
  fastify.addContentTypeParser(
    'application/json',
    { parseAs: 'buffer' },
    (_req, body, done) => done(null, body),
  );

  fastify.addHook('preHandler', async (request: FastifyRequest, reply) => {
    const signature = request.headers['x-signature-sha256'] as string | undefined;

    if (!signature) {
      reply.status(401).send({ error: 'Missing X-Signature-SHA256 header' });
      return;
    }

    const body = request.body as Buffer;
    if (!Buffer.isBuffer(body)) {
      reply.status(400).send({ error: 'Raw body required' });
      return;
    }

    if (!BoomerangSignature.verify(body, signature, options.secret)) {
      reply.status(401).send({ error: 'Invalid signature' });
      return;
    }

    try {
      request.boomerangPayload = JSON.parse(body.toString('utf8')) as BoomerangPayload;
    } catch {
      reply.status(400).send({ error: 'Invalid JSON body' });
    }
  });

  done();
};

export const boomerangPlugin = fp(boomerangPluginFn, {
  fastify: '>=4',
  name: 'boomerang-webhook',
});
