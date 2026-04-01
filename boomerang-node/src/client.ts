import {
  BoomerangError,
  BoomerangUnauthorizedError,
  BoomerangForbiddenError,
  BoomerangConflictError,
  BoomerangServiceUnavailableError,
} from './errors.js';
import type {
  BoomerangClientOptions,
  BoomerangTriggerRequest,
  BoomerangTriggerResponse,
  BoomerangJobStatus,
} from './types.js';

export class BoomerangClient {
  constructor(private readonly options: BoomerangClientOptions) {}

  async trigger(request: BoomerangTriggerRequest): Promise<BoomerangTriggerResponse> {
    const res = await fetch(`${this.options.baseUrl}/sync`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.options.token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(request),
    });
    if (!res.ok) await this.throwTypedError(res);
    return res.json() as Promise<BoomerangTriggerResponse>;
  }

  async poll(jobId: string): Promise<BoomerangJobStatus> {
    const res = await fetch(`${this.options.baseUrl}/sync/${encodeURIComponent(jobId)}`, {
      headers: { 'Authorization': `Bearer ${this.options.token}` },
    });
    if (!res.ok) await this.throwTypedError(res);
    return res.json() as Promise<BoomerangJobStatus>;
  }

  private async throwTypedError(res: Response): Promise<never> {
    const body = await res.json().catch(() => ({})) as Record<string, unknown>;
    const message = (body?.error as string) ?? res.statusText;
    switch (res.status) {
      case 401: throw new BoomerangUnauthorizedError(message);
      case 403: throw new BoomerangForbiddenError(message);
      case 409: throw new BoomerangConflictError(message, body?.retryAfterSeconds as number | undefined);
      case 503: throw new BoomerangServiceUnavailableError(message);
      default:  throw new BoomerangError(res.status, message);
    }
  }
}
