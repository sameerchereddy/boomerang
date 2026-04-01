import { describe, it, expect } from 'vitest';
import {
  BoomerangError,
  BoomerangUnauthorizedError,
  BoomerangForbiddenError,
  BoomerangConflictError,
  BoomerangServiceUnavailableError,
} from '../../src/errors.js';

describe('BoomerangError', () => {
  it('has correct statusCode and message', () => {
    const err = new BoomerangError(500, 'Internal error');
    expect(err.statusCode).toBe(500);
    expect(err.message).toBe('Internal error');
    expect(err.name).toBe('BoomerangError');
  });

  it('is instanceof Error and BoomerangError', () => {
    const err = new BoomerangError(500, 'oops');
    expect(err instanceof Error).toBe(true);
    expect(err instanceof BoomerangError).toBe(true);
  });
});

describe('BoomerangUnauthorizedError', () => {
  it('has statusCode 401', () => {
    const err = new BoomerangUnauthorizedError('Unauthorized');
    expect(err.statusCode).toBe(401);
    expect(err instanceof BoomerangError).toBe(true);
    expect(err instanceof BoomerangUnauthorizedError).toBe(true);
  });
});

describe('BoomerangForbiddenError', () => {
  it('has statusCode 403', () => {
    const err = new BoomerangForbiddenError('Forbidden');
    expect(err.statusCode).toBe(403);
    expect(err instanceof BoomerangError).toBe(true);
    expect(err instanceof BoomerangForbiddenError).toBe(true);
  });
});

describe('BoomerangConflictError', () => {
  it('has statusCode 409 and retryAfterSeconds', () => {
    const err = new BoomerangConflictError('Conflict', 30);
    expect(err.statusCode).toBe(409);
    expect(err.retryAfterSeconds).toBe(30);
    expect(err instanceof BoomerangError).toBe(true);
    expect(err instanceof BoomerangConflictError).toBe(true);
  });

  it('retryAfterSeconds is optional', () => {
    const err = new BoomerangConflictError('Conflict');
    expect(err.retryAfterSeconds).toBeUndefined();
  });
});

describe('BoomerangServiceUnavailableError', () => {
  it('has statusCode 503', () => {
    const err = new BoomerangServiceUnavailableError('Service unavailable');
    expect(err.statusCode).toBe(503);
    expect(err instanceof BoomerangError).toBe(true);
    expect(err instanceof BoomerangServiceUnavailableError).toBe(true);
  });
});
