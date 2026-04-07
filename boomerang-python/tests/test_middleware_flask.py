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
def flask_client():
    from flask import Flask, jsonify

    from boomerang.middleware.flask import boomerang_webhook
    from boomerang.models import BoomerangPayload

    app = Flask(__name__)

    @app.post("/hooks")
    @boomerang_webhook(secret=SECRET)
    def hook(payload: BoomerangPayload):
        return jsonify(job_id=payload.job_id, status=payload.status)

    app.config["TESTING"] = True
    return app.test_client()


class TestFlaskMiddleware:
    def test_valid_signature(self, flask_client):
        resp = flask_client.post(
            "/hooks",
            data=BODY,
            headers={"X-Signature-SHA256": VALID_SIG, "Content-Type": "application/json"},
        )
        assert resp.status_code == 200
        assert resp.get_json()["job_id"] == "j1"

    def test_missing_signature_returns_401(self, flask_client):
        resp = flask_client.post(
            "/hooks",
            data=BODY,
            headers={"Content-Type": "application/json"},
        )
        assert resp.status_code == 401

    def test_invalid_signature_returns_401(self, flask_client):
        resp = flask_client.post(
            "/hooks",
            data=BODY,
            headers={"X-Signature-SHA256": "sha256=" + "a" * 64, "Content-Type": "application/json"},
        )
        assert resp.status_code == 401

    def test_malformed_signature_returns_401(self, flask_client):
        resp = flask_client.post(
            "/hooks",
            data=BODY,
            headers={"X-Signature-SHA256": "bad", "Content-Type": "application/json"},
        )
        assert resp.status_code == 401
