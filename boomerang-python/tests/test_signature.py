import hashlib
import hmac

import pytest

from boomerang.signature import BoomerangSignature


SECRET = "test-secret"
BODY = b'{"jobId":"abc-123","status":"DONE"}'


def _expected_sig(body: bytes = BODY, secret: str = SECRET) -> str:
    digest = hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return f"sha256={digest}"


class TestCompute:
    def test_returns_sha256_prefix(self):
        result = BoomerangSignature.compute(BODY, SECRET)
        assert result.startswith("sha256=")

    def test_lowercase_hex(self):
        result = BoomerangSignature.compute(BODY, SECRET)
        hex_part = result.removeprefix("sha256=")
        assert hex_part == hex_part.lower()
        assert len(hex_part) == 64  # SHA-256 = 64 hex chars

    def test_matches_stdlib_hmac(self):
        assert BoomerangSignature.compute(BODY, SECRET) == _expected_sig()

    def test_different_secret_different_sig(self):
        sig1 = BoomerangSignature.compute(BODY, "secret-a")
        sig2 = BoomerangSignature.compute(BODY, "secret-b")
        assert sig1 != sig2

    def test_different_body_different_sig(self):
        sig1 = BoomerangSignature.compute(b"body-a", SECRET)
        sig2 = BoomerangSignature.compute(b"body-b", SECRET)
        assert sig1 != sig2


class TestVerify:
    def test_valid_signature(self):
        sig = _expected_sig()
        assert BoomerangSignature.verify(BODY, sig, SECRET) is True

    def test_invalid_signature(self):
        bad_sig = "sha256=" + "a" * 64
        assert BoomerangSignature.verify(BODY, bad_sig, SECRET) is False

    def test_wrong_secret(self):
        sig = _expected_sig(secret="wrong")
        assert BoomerangSignature.verify(BODY, sig, SECRET) is False

    def test_malformed_header_raises(self):
        with pytest.raises(ValueError, match="Malformed signature header"):
            BoomerangSignature.verify(BODY, "bad-header", SECRET)

    def test_empty_prefix_raises(self):
        with pytest.raises(ValueError):
            BoomerangSignature.verify(BODY, "abcdef1234", SECRET)

    def test_empty_body(self):
        sig = BoomerangSignature.compute(b"", SECRET)
        assert BoomerangSignature.verify(b"", sig, SECRET) is True
