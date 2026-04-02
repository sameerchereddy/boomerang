from datetime import datetime, timezone

import pytest
from pydantic import ValidationError

from boomerang import (
    BoomerangJobStatus,
    BoomerangPayload,
    BoomerangTriggerRequest,
    BoomerangTriggerResponse,
)


class TestBoomerangTriggerRequest:
    def test_required_fields(self):
        req = BoomerangTriggerRequest(
            worker_url="https://example.com/worker",
            callback_url="https://example.com/callback",
        )
        assert req.worker_url == "https://example.com/worker"
        assert req.callback_url == "https://example.com/callback"
        assert req.callback_secret is None
        assert req.idempotency_key is None

    def test_all_fields(self):
        req = BoomerangTriggerRequest(
            worker_url="https://example.com/worker",
            callback_url="https://example.com/callback",
            callback_secret="secret",
            idempotency_key="key-123",
        )
        assert req.callback_secret == "secret"
        assert req.idempotency_key == "key-123"

    def test_serialise_to_camel_case(self):
        req = BoomerangTriggerRequest(
            worker_url="https://example.com/worker",
            callback_url="https://example.com/callback",
            callback_secret="s",
        )
        data = req.model_dump(by_alias=True, exclude_none=True)
        assert data == {
            "workerUrl": "https://example.com/worker",
            "callbackUrl": "https://example.com/callback",
            "callbackSecret": "s",
        }

    def test_deserialise_from_camel_case(self):
        req = BoomerangTriggerRequest.model_validate(
            {"workerUrl": "https://w.co", "callbackUrl": "https://c.co"}
        )
        assert req.worker_url == "https://w.co"

    def test_immutable(self):
        req = BoomerangTriggerRequest(
            worker_url="https://example.com/worker",
            callback_url="https://example.com/callback",
        )
        with pytest.raises(ValidationError):
            req.worker_url = "https://other.com"


class TestBoomerangTriggerResponse:
    def test_from_json(self):
        resp = BoomerangTriggerResponse.model_validate({"jobId": "abc-123"})
        assert resp.job_id == "abc-123"

    def test_serialise(self):
        resp = BoomerangTriggerResponse(job_id="abc-123")
        assert resp.model_dump(by_alias=True) == {"jobId": "abc-123"}


class TestBoomerangJobStatus:
    def test_pending(self):
        status = BoomerangJobStatus.model_validate(
            {"jobId": "j1", "status": "PENDING"}
        )
        assert status.status == "PENDING"
        assert status.completed_at is None
        assert status.result is None

    def test_done(self):
        status = BoomerangJobStatus.model_validate(
            {
                "jobId": "j1",
                "status": "DONE",
                "completedAt": "2026-03-22T10:00:18Z",
                "result": {"key": "value"},
            }
        )
        assert status.status == "DONE"
        assert status.result == {"key": "value"}

    def test_failed(self):
        status = BoomerangJobStatus.model_validate(
            {"jobId": "j1", "status": "FAILED", "error": "boom"}
        )
        assert status.status == "FAILED"
        assert status.error == "boom"

    def test_invalid_status_rejected(self):
        with pytest.raises(ValidationError):
            BoomerangJobStatus.model_validate(
                {"jobId": "j1", "status": "UNKNOWN"}
            )


class TestBoomerangPayload:
    def test_done_payload(self):
        payload = BoomerangPayload.model_validate(
            {
                "boomerangVersion": "1",
                "jobId": "j1",
                "status": "DONE",
                "completedAt": "2026-03-22T10:00:18Z",
                "result": {"report": "url"},
            }
        )
        assert payload.boomerang_version == "1"
        assert payload.job_id == "j1"
        assert payload.status == "DONE"
        assert isinstance(payload.completed_at, datetime)
        assert payload.result == {"report": "url"}
        assert payload.error is None

    def test_failed_payload(self):
        payload = BoomerangPayload.model_validate(
            {
                "boomerangVersion": "1",
                "jobId": "j1",
                "status": "FAILED",
                "completedAt": "2026-03-22T10:00:18Z",
                "error": "timeout",
            }
        )
        assert payload.error == "timeout"
        assert payload.result is None

    def test_immutable(self):
        payload = BoomerangPayload.model_validate(
            {
                "boomerangVersion": "1",
                "jobId": "j1",
                "status": "DONE",
                "completedAt": "2026-03-22T10:00:18Z",
            }
        )
        with pytest.raises(ValidationError):
            payload.job_id = "other"

    def test_serialise_to_camel_case(self):
        payload = BoomerangPayload(
            boomerang_version="1",
            job_id="j1",
            status="DONE",
            completed_at=datetime(2026, 3, 22, 10, 0, 18, tzinfo=timezone.utc),
        )
        data = payload.model_dump(by_alias=True, exclude_none=True)
        assert "boomerangVersion" in data
        assert "jobId" in data
        assert "completedAt" in data
