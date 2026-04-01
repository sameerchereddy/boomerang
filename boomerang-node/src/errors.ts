export class BoomerangError extends Error {
  constructor(public readonly statusCode: number, message: string) {
    super(message);
    this.name = 'BoomerangError';
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

export class BoomerangUnauthorizedError extends BoomerangError {
  constructor(message: string) {
    super(401, message);
    this.name = 'BoomerangUnauthorizedError';
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

export class BoomerangForbiddenError extends BoomerangError {
  constructor(message: string) {
    super(403, message);
    this.name = 'BoomerangForbiddenError';
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

export class BoomerangConflictError extends BoomerangError {
  constructor(message: string, public readonly retryAfterSeconds?: number) {
    super(409, message);
    this.name = 'BoomerangConflictError';
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

export class BoomerangServiceUnavailableError extends BoomerangError {
  constructor(message: string) {
    super(503, message);
    this.name = 'BoomerangServiceUnavailableError';
    Object.setPrototypeOf(this, new.target.prototype);
  }
}
