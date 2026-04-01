import { createHmac, timingSafeEqual } from 'node:crypto';

export class BoomerangSignature {
  static verify(body: Buffer, signatureHeader: string, secret: string): boolean {
    const expected = BoomerangSignature.compute(body, secret);
    const a = Buffer.from(expected, 'utf8');
    const b = Buffer.from(signatureHeader, 'utf8');
    if (a.length !== b.length) return false;
    return timingSafeEqual(a, b);
  }

  static compute(body: Buffer, secret: string): string {
    return 'sha256=' + createHmac('sha256', secret).update(body).digest('hex');
  }
}
