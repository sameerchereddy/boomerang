"""Flask decorator for verifying Boomerang webhook signatures."""

from __future__ import annotations

import functools
from typing import Any, Callable

from ..models import BoomerangPayload
from ..signature import BoomerangSignature


def boomerang_webhook(secret: str) -> Callable:
    """Decorator that verifies the webhook signature and injects a
    :class:`BoomerangPayload` as the first positional argument.

    Usage::

        @app.post("/hooks/sync-done")
        @boomerang_webhook(secret=os.environ["WEBHOOK_SECRET"])
        def on_sync_done(payload: BoomerangPayload):
            ...
    """

    def decorator(fn: Callable[..., Any]) -> Callable[..., Any]:
        @functools.wraps(fn)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            from flask import abort, request  # noqa: lazy import

            signature = request.headers.get("X-Signature-SHA256")
            if not signature:
                abort(401, description="Missing X-Signature-SHA256 header")

            body = request.get_data()

            try:
                valid = BoomerangSignature.verify(body, signature, secret)
            except ValueError:
                abort(401, description="Malformed signature header")

            if not valid:
                abort(401, description="Invalid signature")

            payload = BoomerangPayload.model_validate_json(body)
            return fn(payload, *args, **kwargs)

        return wrapper

    return decorator
