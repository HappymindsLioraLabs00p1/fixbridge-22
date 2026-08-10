"""Orchestrates: fetch images -> process -> assess -> validate.

Holds the content-hash cache. Photographs of a job are re-analysed surprisingly often — a retry, a
duplicate submission, an admin re-running an assessment — and each repeat is a real charge for an
identical answer.
"""

from __future__ import annotations

import time
from collections import OrderedDict
from typing import Dict, List, Optional, Tuple

import httpx
import structlog

from app.core.config import get_settings
from app.schemas.assessment import AssessmentResponse
from app.schemas.image import ProcessedImageInfo
from app.services import image_processor
from app.services.vision_service import VisionService

log = structlog.get_logger()


class _Cache:
    """Small in-memory LRU keyed by (description, image hashes).

    In memory is the right scope for a stateless service that scales horizontally: a cache miss
    costs one model call, never correctness. A shared cache (Redis) is only worth adding if the
    duplicate rate across instances proves high.
    """

    def __init__(self, max_entries: int = 256, ttl_seconds: int = 3600) -> None:
        self._data: "OrderedDict[str, Tuple[float, AssessmentResponse]]" = OrderedDict()
        self._max = max_entries
        self._ttl = ttl_seconds

    def get(self, key: str) -> Optional[AssessmentResponse]:
        hit = self._data.get(key)
        if not hit:
            return None
        stored_at, value = hit
        if time.time() - stored_at > self._ttl:
            self._data.pop(key, None)
            return None
        self._data.move_to_end(key)
        return value

    def put(self, key: str, value: AssessmentResponse) -> None:
        self._data[key] = (time.time(), value)
        self._data.move_to_end(key)
        while len(self._data) > self._max:
            self._data.popitem(last=False)


_cache = _Cache()


class AiAssessmentService:
    def __init__(self) -> None:
        self.settings = get_settings()
        self.vision = VisionService()

    async def fetch(self, url: str) -> bytes:
        """Download one image from a signed URL. Bounded in both time and size so a slow or hostile
        source cannot occupy a worker indefinitely."""
        limit = self.settings.max_upload_bytes
        async with httpx.AsyncClient(timeout=self.settings.download_timeout_seconds) as client:
            async with client.stream("GET", url) as response:
                response.raise_for_status()
                declared = response.headers.get("content-length")
                if declared and int(declared) > limit:
                    raise image_processor.ImageRejected("Image exceeds the size limit")
                chunks, total = [], 0
                async for chunk in response.aiter_bytes():
                    total += len(chunk)
                    if total > limit:
                        raise image_processor.ImageRejected("Image exceeds the size limit")
                    chunks.append(chunk)
                return b"".join(chunks)

    async def process_urls(self, urls: List[str], correlation_id: Optional[str] = None,
                           generate_thumbnails: bool = True
                           ) -> Tuple[List[bytes], List[ProcessedImageInfo], List[Dict[str, str]]]:
        """Fetch and process images, capping how many are analysed.

        One bad image doesn't fail the assessment — the description alone is often enough, and a
        partial answer beats none. Failures are reported so the caller can see what was skipped.
        """
        processed_bytes: List[bytes] = []
        infos: List[ProcessedImageInfo] = []
        failures: List[Dict[str, str]] = []

        capped = urls[: self.settings.max_images_per_assessment]
        if len(urls) > len(capped):
            log.info("images_capped", requested=len(urls), used=len(capped),
                     correlation_id=correlation_id)

        for url in capped:
            try:
                raw = await self.fetch(str(url))
                result = image_processor.process(raw, source=None,
                                                 generate_thumbnail=generate_thumbnails)
                processed_bytes.append(result.data)
                infos.append(result.info)
                log.info("image_processed", correlation_id=correlation_id,
                         original_bytes=result.info.original_bytes,
                         processed_bytes=result.info.processed_bytes,
                         had_gps=result.info.had_gps)
            except image_processor.ImageRejected as exc:
                failures.append({"reason": str(exc)})
                log.warning("image_rejected", reason=str(exc), correlation_id=correlation_id)
            except (httpx.HTTPError, httpx.TimeoutException) as exc:
                failures.append({"reason": f"Could not retrieve the image: {exc.__class__.__name__}"})
                log.warning("image_fetch_failed", error=str(exc), correlation_id=correlation_id)
        return processed_bytes, infos, failures

    async def analyze_from_urls(self, description: str, urls: List[str],
                                correlation_id: Optional[str] = None) -> AssessmentResponse:
        started = time.perf_counter()
        images, infos, _failures = await self.process_urls(urls, correlation_id)

        cache_key = "|".join([description.strip().lower(), *(i.content_hash for i in infos)])
        cached = _cache.get(cache_key)
        if cached is not None:
            log.info("assessment_cache_hit", correlation_id=correlation_id)
            copy = cached.model_copy(deep=True)
            copy.cached = True
            copy.correlation_id = correlation_id
            return copy

        assessment = self.vision.assess(description, images, correlation_id)
        assessment.images = infos
        assessment.correlation_id = correlation_id
        _cache.put(cache_key, assessment)

        log.info("assessment_complete", correlation_id=correlation_id,
                 images=len(images), urgency=assessment.urgency,
                 latency_ms=round((time.perf_counter() - started) * 1000))
        return assessment
