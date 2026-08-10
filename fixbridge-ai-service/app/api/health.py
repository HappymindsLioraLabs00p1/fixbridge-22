"""Liveness and readiness. Deliberately unauthenticated so the platform can probe them."""

from fastapi import APIRouter

from app.core.config import get_settings

router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict:
    """Is the process alive. Must not touch dependencies — a failing dependency should not cause
    the platform to restart-loop a perfectly healthy container."""
    return {"status": "ok", "service": "fixbridge-ai-service"}


@router.get("/ready")
async def ready() -> dict:
    """Is the process ready to serve, and configured as intended."""
    settings = get_settings()
    return {
        "status": "ready",
        "environment": settings.environment,
        "stub_mode": settings.stub_mode,
        "model": settings.vision_model,
        "auth_configured": bool(settings.service_auth_token),
        "limits": {
            "max_images_per_assessment": settings.max_images_per_assessment,
            "max_dimension": settings.max_dimension,
            "max_upload_mb": settings.max_upload_bytes // 1024 // 1024,
        },
    }
