from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class _CamelModel(BaseModel):
    """Base model that serialises snake_case fields to camelCase JSON."""

    model_config = ConfigDict(
        populate_by_name=True,
        frozen=True,
    )


class BoomerangTriggerRequest(_CamelModel):
    """Request body for ``POST /sync``."""

    worker_url: str = Field(alias="workerUrl")
    callback_url: str = Field(alias="callbackUrl")
    callback_secret: str | None = Field(default=None, alias="callbackSecret")
    idempotency_key: str | None = Field(default=None, alias="idempotencyKey")


class BoomerangTriggerResponse(_CamelModel):
    """Response body returned by ``POST /sync``."""

    job_id: str = Field(alias="jobId")


class BoomerangJobStatus(_CamelModel):
    """Response body returned by ``GET /sync/{jobId}``."""

    job_id: str = Field(alias="jobId")
    status: Literal["PENDING", "IN_PROGRESS", "DONE", "FAILED"]
    created_at: str | None = Field(default=None, alias="createdAt")
    completed_at: str | None = Field(default=None, alias="completedAt")
    result: dict[str, Any] | None = None
    error: str | None = None


class BoomerangPayload(_CamelModel):
    """Webhook callback payload delivered to the consumer's ``callbackUrl``.

    This model is immutable (frozen) so middleware can safely pass it around.
    """

    boomerang_version: str = Field(alias="boomerangVersion")
    job_id: str = Field(alias="jobId")
    status: Literal["DONE", "FAILED"]
    completed_at: datetime = Field(alias="completedAt")
    result: dict[str, Any] | None = None
    error: str | None = None
