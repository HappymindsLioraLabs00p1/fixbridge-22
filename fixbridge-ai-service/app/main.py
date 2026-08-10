"""FixBridge AI / Image Processing service.

A separate, stateless service that handles image processing and AI assessment for FixBridge. The
Java Spring Boot application remains the primary backend and owns all business state — this service
holds no database and never writes to FixBridge's tables.
"""

from __future__ import annotations

import time
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.api import assessment, health, images
from app.core.config import get_settings
from app.core.logging import configure_logging, correlation_id, new_correlation_id

settings = get_settings()
configure_logging(settings.log_level)
log = structlog.get_logger()

@asynccontextmanager
async def lifespan(_app: FastAPI):
    log.info("service_start", environment=settings.environment, stub_mode=settings.stub_mode,
             model=settings.vision_model, auth_configured=bool(settings.service_auth_token))
    yield


app = FastAPI(
    lifespan=lifespan,
    title="FixBridge AI & Image Service",
    description="Image processing, optimisation and AI assessment. Called by the Java backend.",
    version="1.0.0",
    # The API schema is not published: this service is internal, and advertising its surface adds
    # nothing but reconnaissance value.
    docs_url="/docs" if settings.environment == "development" else None,
    openapi_url="/openapi.json" if settings.environment == "development" else None,
)


@app.middleware("http")
async def observability(request: Request, call_next):
    """Attach a correlation id and record latency for every request."""
    cid = request.headers.get("X-Correlation-Id") or new_correlation_id()
    correlation_id.set(cid)
    started = time.perf_counter()
    try:
        response = await call_next(request)
    except Exception:
        log.exception("unhandled_error", path=request.url.path)
        # Never leak a stack trace to a caller.
        return JSONResponse(status_code=500,
                            content={"detail": "The service encountered an error.",
                                     "correlation_id": cid})
    duration = round((time.perf_counter() - started) * 1000)
    response.headers["X-Correlation-Id"] = cid
    log.info("request", method=request.method, path=request.url.path,
             status=response.status_code, latency_ms=duration)
    return response


app.include_router(health.router)
app.include_router(images.router)
app.include_router(assessment.router)
