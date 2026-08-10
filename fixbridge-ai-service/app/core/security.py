"""Service-to-service authentication and rate limiting.

Only the Java backend calls this service; it is never exposed to the browser or the mobile app.
A shared bearer token is proportionate for that, but the comparison is constant-time so the token
cannot be recovered by timing.
"""

from __future__ import annotations

import secrets
import time
from collections import defaultdict, deque
from typing import Deque, Dict

from fastapi import Header, HTTPException, Request, status

from app.core.config import get_settings

_requests: Dict[str, Deque[float]] = defaultdict(deque)


async def require_service_token(authorization: str = Header(default="")) -> None:
    settings = get_settings()

    # An unset token in development leaves the service open deliberately; in any other environment
    # a missing token is a misconfiguration and the service must refuse to serve.
    if not settings.service_auth_token:
        if settings.environment == "development":
            return
        raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR,
                            "Service authentication is not configured")

    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not secrets.compare_digest(token, settings.service_auth_token):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid service credentials")


async def rate_limit(request: Request) -> None:
    """Fixed-window limit per caller. The caller is Java, so this is a runaway-loop guard rather
    than an anti-abuse control."""
    settings = get_settings()
    key = request.client.host if request.client else "unknown"
    now = time.time()

    window = _requests[key]
    while window and now - window[0] > 60:
        window.popleft()
    if len(window) >= settings.rate_limit_per_minute:
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS,
                            "Too many requests. Please retry shortly.")
    window.append(now)
