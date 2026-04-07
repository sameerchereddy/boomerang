"""FastAPI dependency for verifying Boomerang webhook signatures."""

from typing import Callable

from ..models import BoomerangPayload
from ..signature import BoomerangSignature


def boomerang_webhook(secret: str) -> Callable:
    """Return a FastAPI dependency callable that verifies the webhook
    signature and returns a :class:`BoomerangPayload`.

    Usage::

        from fastapi import Depends

        @app.post("/hooks/sync-done")
        async def on_sync_done(
            payload: BoomerangPayload = Depends(boomerang_webhook(secret)),
        ):
            ...
    """
    from fastapi import HTTPException, Request

    async def _verify(request: Request) -> BoomerangPayload:
        signature = request.headers.get("x-signature-sha256")
        if not signature:
            raise HTTPException(status_code=401, detail="Missing X-Signature-SHA256 header")

        body = await request.body()

        try:
            valid = BoomerangSignature.verify(body, signature, secret)
        except ValueError:
            raise HTTPException(status_code=401, detail="Malformed signature header")

        if not valid:
            raise HTTPException(status_code=401, detail="Invalid signature")

        return BoomerangPayload.model_validate_json(body)

    return _verify
