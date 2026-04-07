import json

import pytest

from boomerang.signature import BoomerangSignature

SECRET = "test-secret"
PAYLOAD = {
    "boomerangVersion": "1",
    "jobId": "j1",
    "status": "DONE",
    "completedAt": "2026-03-22T10:00:18Z",
    "result": {"key": "value"},
}
BODY = json.dumps(PAYLOAD).encode()
VALID_SIG = BoomerangSignature.compute(BODY, SECRET)


@pytest.fixture()
def fastapi_client():
    from fastapi import Depends, FastAPI
    from fastapi.testclient import TestClient

    from boomerang.middleware.fastapi import boomerang_webhook
    from boomerang.models import BoomerangPayload

    app = FastAPI()

    @app.post("/hooks")
    async def hook(payload: BoomerangPayload = Depends(boomerang_webhook(SECRET))):
        return {"job_id": payload.job_id, "status": payload.status}

    return TestClient(app)


class TestFastAPIMiddleware:
    def test_valid_signature(self, fastapi_client):
        resp = fastapi_client.post(
            "/hooks",
            content=BODY,
            headers={"X-Signature-SHA256": VALID_SIG, "Content-Type": "application/json"},
        )
        assert resp.status_code == 200
        assert resp.json()["job_id"] == "j1"

    def test_missing_signature_returns_401(self, fastapi_client):
        resp = fastapi_client.post(
            "/hooks",
            content=BODY,
            headers={"Content-Type": "application/json"},
        )
        assert resp.status_code == 401

    def test_invalid_signature_returns_401(self, fastapi_client):
        resp = fastapi_client.post(
            "/hooks",
            content=BODY,
            headers={"X-Signature-SHA256": "sha256=" + "a" * 64, "Content-Type": "application/json"},
        )
        assert resp.status_code == 401

    def test_malformed_signature_returns_401(self, fastapi_client):
        resp = fastapi_client.post(
            "/hooks",
            content=BODY,
            headers={"X-Signature-SHA256": "bad", "Content-Type": "application/json"},
        )
        assert resp.status_code == 401
