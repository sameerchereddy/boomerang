import { describe, it, expect } from 'vitest';
import { BoomerangSignature } from '../../src/signature.js';

const SECRET = 'my-test-secret-that-is-32-chars!!';
const BODY = Buffer.from(JSON.stringify({ jobId: 'abc', status: 'DONE' }), 'utf8');

describe('BoomerangSignature.compute', () => {
  it('returns sha256= prefixed hex string', () => {
    const sig = BoomerangSignature.compute(BODY, SECRET);
    expect(sig).toMatch(/^sha256=[0-9a-f]{64}$/);
  });

  it('is deterministic', () => {
    expect(BoomerangSignature.compute(BODY, SECRET)).toBe(BoomerangSignature.compute(BODY, SECRET));
  });

  it('differs for different secrets', () => {
    expect(BoomerangSignature.compute(BODY, SECRET)).not.toBe(
      BoomerangSignature.compute(BODY, 'different-secret-that-is-32char!'),
    );
  });

  it('differs for different bodies', () => {
    const other = Buffer.from('{}', 'utf8');
    expect(BoomerangSignature.compute(BODY, SECRET)).not.toBe(
      BoomerangSignature.compute(other, SECRET),
    );
  });
});

describe('BoomerangSignature.verify', () => {
  it('returns true for valid signature', () => {
    const sig = BoomerangSignature.compute(BODY, SECRET);
    expect(BoomerangSignature.verify(BODY, sig, SECRET)).toBe(true);
  });

  it('returns false for tampered body', () => {
    const sig = BoomerangSignature.compute(BODY, SECRET);
    const tampered = Buffer.from('{"jobId":"evil"}', 'utf8');
    expect(BoomerangSignature.verify(tampered, sig, SECRET)).toBe(false);
  });

  it('returns false for wrong secret', () => {
    const sig = BoomerangSignature.compute(BODY, SECRET);
    expect(BoomerangSignature.verify(BODY, sig, 'wrong-secret-that-is-32-chars!!!!')).toBe(false);
  });

  it('returns false for signature of different length', () => {
    expect(BoomerangSignature.verify(BODY, 'sha256=short', SECRET)).toBe(false);
  });

  it('returns false for empty signature', () => {
    expect(BoomerangSignature.verify(BODY, '', SECRET)).toBe(false);
  });
});
