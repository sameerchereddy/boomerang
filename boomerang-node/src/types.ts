export interface BoomerangClientOptions {
  baseUrl: string;
  token: string;
}

export interface BoomerangTriggerRequest {
  workerUrl?: string;
  callbackUrl: string;
  callbackSecret?: string;
  idempotencyKey?: string;
  payload?: Record<string, unknown>;
  messageVersion?: string;
}

export interface BoomerangTriggerResponse {
  jobId: string;
}

export interface BoomerangJobStatus {
  jobId: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'FAILED';
  createdAt: string;
  completedAt: string | null;
}

export interface BoomerangPayload {
  jobId: string;
  status: 'DONE' | 'FAILED';
  completedAt: string;
  result?: unknown;
  error?: string;
}
