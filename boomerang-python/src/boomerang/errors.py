from __future__ import annotations


class BoomerangError(Exception):
    """Base class for all Boomerang SDK errors."""

    status_code: int = 0

    def __init__(self, status_code: int, message: str) -> None:
        self.status_code = status_code
        self.message = message
        super().__init__(message)

    def __str__(self) -> str:
        return f"[{self.status_code}] {self.message}"


class BoomerangUnauthorizedError(BoomerangError):
    """401 — missing or invalid JWT."""

    def __init__(self, message: str = "Unauthorized") -> None:
        super().__init__(401, message)


class BoomerangForbiddenError(BoomerangError):
    """403 — callbackUrl or workerUrl not in the server's allowlist."""

    def __init__(self, message: str = "Forbidden") -> None:
        super().__init__(403, message)


class BoomerangConflictError(BoomerangError):
    """409 — duplicate job submitted within idempotency cooldown."""

    def __init__(self, message: str = "Conflict", retry_after_seconds: int | None = None) -> None:
        super().__init__(409, message)
        self.retry_after_seconds = retry_after_seconds


class BoomerangServiceUnavailableError(BoomerangError):
    """503 — Boomerang worker pool is saturated."""

    def __init__(self, message: str = "Service Unavailable") -> None:
        super().__init__(503, message)


STATUS_CODE_TO_ERROR: dict[int, type[BoomerangError]] = {
    401: BoomerangUnauthorizedError,
    403: BoomerangForbiddenError,
    409: BoomerangConflictError,
    503: BoomerangServiceUnavailableError,
}


def raise_for_status(status_code: int, message: str) -> None:
    """Raise the appropriate ``BoomerangError`` subclass for *status_code*."""
    error_cls = STATUS_CODE_TO_ERROR.get(status_code)
    if error_cls is not None:
        raise error_cls(message)
    raise BoomerangError(status_code, message)
