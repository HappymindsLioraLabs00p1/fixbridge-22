"""Assessment endpoints. Java is the only caller."""

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.core.logging import correlation_id, new_correlation_id
from app.core.security import rate_limit, require_service_token
from app.schemas.assessment import AnalyzeFromUrlRequest, AssessmentResponse
from app.services.ai_assessment_service import AiAssessmentService

router = APIRouter(prefix="/v1/assessment", tags=["assessment"],
                   dependencies=[Depends(require_service_token), Depends(rate_limit)])

service = AiAssessmentService()


@router.post("/analyze-from-url", response_model=AssessmentResponse)
async def analyze_from_url(payload: AnalyzeFromUrlRequest, request: Request) -> AssessmentResponse:
    """Fetch the images from their signed URLs, process them, and assess.

    This is the endpoint Java calls: it already holds signed URLs, so passing references avoids
    pushing image bytes through a third hop.
    """
    cid = payload.correlation_id or request.headers.get("X-Correlation-Id") or new_correlation_id()
    correlation_id.set(cid)
    try:
        return await service.analyze_from_urls(payload.description,
                                               [str(u) for u in payload.image_urls], cid)
    except ValueError as exc:
        # The model returned something unusable. A 502 tells Java this is an upstream fault it
        # should retry, not a bad request it should reject.
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc


@router.post("/analyze", response_model=AssessmentResponse)
async def analyze(payload: AnalyzeFromUrlRequest, request: Request) -> AssessmentResponse:
    """Alias of analyze-from-url, kept because the agreed contract names both."""
    return await analyze_from_url(payload, request)
