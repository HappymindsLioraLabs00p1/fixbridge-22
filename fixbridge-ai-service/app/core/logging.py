"""Structured logging with a correlation id shared with the Java service.

Java generates the id and passes it as X-Correlation-Id; every line this service emits carries it,
so one job can be followed across both services in a log search.
"""

from __future__ import annotations

import logging
import sys
import uuid
from contextvars import ContextVar

import structlog

correlation_id: ContextVar[str] = ContextVar("correlation_id", default="")


def configure_logging(level: str = "INFO") -> None:
    logging.basicConfig(format="%(message)s", stream=sys.stdout, level=getattr(logging, level, logging.INFO))
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            _add_correlation_id,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(getattr(logging, level, logging.INFO)),
        cache_logger_on_first_use=True,
    )


def _add_correlation_id(_logger, _name, event_dict):
    cid = correlation_id.get()
    if cid:
        event_dict.setdefault("correlation_id", cid)
    return event_dict


def new_correlation_id() -> str:
    """Used only when Java didn't supply one — a request should always be traceable."""
    return f"AI-{uuid.uuid4().hex[:12]}"
