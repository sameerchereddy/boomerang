from __future__ import annotations

from urllib.parse import quote

import httpx

from .errors import (
    BoomerangConflictError,
    BoomerangError,
    BoomerangForbiddenError,
    BoomerangServiceUnavailableError,
    BoomerangUnauthorizedError,
)
from .models import (
    BoomerangJobStatus,
    BoomerangTriggerRequest,
    BoomerangTriggerResponse,
)

_STATUS_MAP: dict[int, type[BoomerangError]] = {
    401: BoomerangUnauthorizedError,
    403: BoomerangForbiddenError,
    409: BoomerangConflictError,
    503: BoomerangServiceUnavailableError,
}


class BoomerangClient:
    """Thin HTTP client for the Boomerang async webhook service."""

    def __init__(self, base_url: str, token: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._token = token

    # --- headers ---

    def _auth_headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self._token}",
            "Content-Type": "application/json",
        }

    # --- error handling ---

    @staticmethod
    def _raise_for_status(response: httpx.Response) -> None:
        if response.is_success:
            return
        body = response.json() if response.content else {}
        message = body.get("error", response.reason_phrase or "Unknown error")
        error_cls = _STATUS_MAP.get(response.status_code)
        if error_cls is BoomerangConflictError:
            raise BoomerangConflictError(
                message,
                retry_after_seconds=body.get("retryAfterSeconds"),
            )
        if error_cls is not None:
            raise error_cls(message)
        raise BoomerangError(response.status_code, message)

    # --- sync ---

    def trigger(
        self,
        worker_url: str,
        callback_url: str,
        callback_secret: str | None = None,
        idempotency_key: str | None = None,
    ) -> BoomerangTriggerResponse:
        """Submit a job via ``POST /sync``. Returns the assigned job ID."""
        req = BoomerangTriggerRequest(
            worker_url=worker_url,
            callback_url=callback_url,
            callback_secret=callback_secret,
            idempotency_key=idempotency_key,
        )
        response = httpx.post(
            f"{self._base_url}/sync",
            headers=self._auth_headers(),
            content=req.model_dump_json(by_alias=True, exclude_none=True),
        )
        self._raise_for_status(response)
        return BoomerangTriggerResponse.model_validate(response.json())

    def poll(self, job_id: str) -> BoomerangJobStatus:
        """Poll job status via ``GET /sync/{jobId}``."""
        response = httpx.get(
            f"{self._base_url}/sync/{quote(job_id, safe='')}",
            headers=self._auth_headers(),
        )
        self._raise_for_status(response)
        return BoomerangJobStatus.model_validate(response.json())

    # --- async ---

    async def trigger_async(
        self,
        worker_url: str,
        callback_url: str,
        callback_secret: str | None = None,
        idempotency_key: str | None = None,
    ) -> BoomerangTriggerResponse:
        """Async variant of :meth:`trigger`."""
        req = BoomerangTriggerRequest(
            worker_url=worker_url,
            callback_url=callback_url,
            callback_secret=callback_secret,
            idempotency_key=idempotency_key,
        )
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self._base_url}/sync",
                headers=self._auth_headers(),
                content=req.model_dump_json(by_alias=True, exclude_none=True),
            )
        self._raise_for_status(response)
        return BoomerangTriggerResponse.model_validate(response.json())

    async def poll_async(self, job_id: str) -> BoomerangJobStatus:
        """Async variant of :meth:`poll`."""
        async with httpx.AsyncClient() as client:
            response = await client.get(
                f"{self._base_url}/sync/{quote(job_id, safe='')}",
                headers=self._auth_headers(),
            )
        self._raise_for_status(response)
        return BoomerangJobStatus.model_validate(response.json())
