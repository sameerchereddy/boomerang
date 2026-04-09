import json

import httpx
import pytest

from boomerang import (
    BoomerangConflictError,
    BoomerangForbiddenError,
    BoomerangServiceUnavailableError,
    BoomerangUnauthorizedError,
)
from boomerang.client import BoomerangClient


BASE = "https://boomerang.test"
TOKEN = "test-jwt"


def _client() -> BoomerangClient:
    return BoomerangClient(base_url=BASE, token=TOKEN)


def _mock_response(status: int, body: dict | None = None) -> httpx.Response:
    return httpx.Response(
        status_code=status,
        json=body or {},
        request=httpx.Request("GET", BASE),
    )


# ── trigger ──────────────────────────────────────────────────────────


class TestTrigger:
    def test_success(self, httpx_mock):
        httpx_mock.add_response(
            url=f"{BASE}/sync",
            method="POST",
            status_code=202,
            json={"jobId": "j1"},
        )
        resp = _client().trigger(
            worker_url="https://w.co/work",
            callback_url="https://c.co/hook",
        )
        assert resp.job_id == "j1"

    def test_sends_auth_header(self, httpx_mock):
        httpx_mock.add_response(
            url=f"{BASE}/sync",
            method="POST",
            status_code=202,
            json={"jobId": "j1"},
        )
        _client().trigger(
            worker_url="https://w.co/work",
            callback_url="https://c.co/hook",
        )
        request = httpx_mock.get_request()
        assert request.headers["authorization"] == f"Bearer {TOKEN}"

    def test_sends_camel_case_body(self, httpx_mock):
        httpx_mock.add_response(
            url=f"{BASE}/sync",
            method="POST",
            status_code=202,
            json={"jobId": "j1"},
        )
        _client().trigger(
            worker_url="https://w.co/work",
            callback_url="https://c.co/hook",
            callback_secret="s",
            idempotency_key="k",
        )
        body = json.loads(httpx_mock.get_request().content)
        assert body == {
            "workerUrl": "https://w.co/work",
            "callbackUrl": "https://c.co/hook",
            "callbackSecret": "s",
            "idempotencyKey": "k",
        }

    def test_excludes_none_fields(self, httpx_mock):
        httpx_mock.add_response(
            url=f"{BASE}/sync",
            method="POST",
            status_code=202,
            json={"jobId": "j1"},
        )
        _client().trigger(
            worker_url="https://w.co/work",
            callback_url="https://c.co/hook",
        )
        body = json.loads(httpx_mock.get_request().content)
        assert "callbackSecret" not in body
        assert "idempotencyKey" not in body


# ── poll ─────────────────────────────────────────────────────────────


class TestPoll:
    def test_success(self, httpx_mock):
        httpx_mock.add_response(
            url=f"{BASE}/sync/j1",
            method="GET",
            json={"jobId": "j1", "status": "DONE", "completedAt": "2026-01-01T00:00:00Z"},
        )
        status = _client().poll("j1")
        assert status.job_id == "j1"
        assert status.status == "DONE"

    def test_url_encodes_job_id(self, httpx_mock):
        httpx_mock.add_response(
            url=f"{BASE}/sync/a%2Fb",
            method="GET",
            json={"jobId": "a/b", "status": "PENDING"},
        )
        status = _client().poll("a/b")
        assert status.job_id == "a/b"


# ── error mapping ───────────────────────────────────────────────────


class TestErrorMapping:
    @pytest.mark.parametrize(
        "status,error_cls",
        [
            (401, BoomerangUnauthorizedError),
            (403, BoomerangForbiddenError),
            (409, BoomerangConflictError),
            (503, BoomerangServiceUnavailableError),
        ],
    )
    def test_known_errors(self, httpx_mock, status, error_cls):
        httpx_mock.add_response(
            url=f"{BASE}/sync",
            method="POST",
            status_code=status,
            json={"error": "boom"},
        )
        with pytest.raises(error_cls, match="boom"):
            _client().trigger(
                worker_url="https://w.co/work",
                callback_url="https://c.co/hook",
            )

    def test_conflict_retry_after(self, httpx_mock):
        httpx_mock.add_response(
            url=f"{BASE}/sync",
            method="POST",
            status_code=409,
            json={"error": "dup", "retryAfterSeconds": 30},
        )
        with pytest.raises(BoomerangConflictError) as exc_info:
            _client().trigger(
                worker_url="https://w.co/work",
                callback_url="https://c.co/hook",
            )
        assert exc_info.value.retry_after_seconds == 30

    def test_unknown_error(self, httpx_mock):
        from boomerang.errors import BoomerangError

        httpx_mock.add_response(
            url=f"{BASE}/sync",
            method="POST",
            status_code=500,
            json={"error": "internal"},
        )
        with pytest.raises(BoomerangError) as exc_info:
            _client().trigger(
                worker_url="https://w.co/work",
                callback_url="https://c.co/hook",
            )
        assert exc_info.value.status_code == 500


# ── async ────────────────────────────────────────────────────────────


class TestAsync:
    @pytest.mark.asyncio
    async def test_trigger_async(self, httpx_mock):
        httpx_mock.add_response(
            url=f"{BASE}/sync",
            method="POST",
            status_code=202,
            json={"jobId": "j1"},
        )
        resp = await _client().trigger_async(
            worker_url="https://w.co/work",
            callback_url="https://c.co/hook",
        )
        assert resp.job_id == "j1"

    @pytest.mark.asyncio
    async def test_poll_async(self, httpx_mock):
        httpx_mock.add_response(
            url=f"{BASE}/sync/j1",
            method="GET",
            json={"jobId": "j1", "status": "PENDING"},
        )
        status = await _client().poll_async("j1")
        assert status.status == "PENDING"
