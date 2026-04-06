from __future__ import annotations

import hashlib
import hmac


class BoomerangSignature:
    """HMAC-SHA256 signature verification for Boomerang webhooks."""

    _PREFIX = "sha256="

    @staticmethod
    def verify(body: bytes, signature_header: str, secret: str) -> bool:
        """Return ``True`` if *signature_header* is a valid HMAC for *body*.

        Uses constant-time comparison to prevent timing attacks.

        Raises ``ValueError`` if *signature_header* does not start with
        ``sha256=``.
        """
        if not signature_header.startswith(BoomerangSignature._PREFIX):
            raise ValueError(
                f"Malformed signature header: expected 'sha256=...' prefix, "
                f"got {signature_header!r}"
            )
        expected = BoomerangSignature.compute(body, secret)
        return hmac.compare_digest(expected, signature_header)

    @staticmethod
    def compute(body: bytes, secret: str) -> str:
        """Return the signature header value: ``sha256=<lowercase hex>``."""
        digest = hmac.new(
            secret.encode("utf-8"),
            body,
            hashlib.sha256,
        ).hexdigest()
        return f"sha256={digest}"
