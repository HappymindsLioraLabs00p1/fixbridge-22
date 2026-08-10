"""Image processing endpoints, usable independently of an AI assessment."""

from fastapi import APIRouter, Depends, File, HTTPException, Request, UploadFile, status

from app.core.logging import correlation_id, new_correlation_id
from app.core.security import rate_limit, require_service_token
from app.schemas.image import ProcessImagesRequest, ProcessImagesResponse, ProcessedImageInfo
from app.services import image_processor
from app.services.ai_assessment_service import AiAssessmentService

router = APIRouter(prefix="/v1/images", tags=["images"],
                   dependencies=[Depends(require_service_token), Depends(rate_limit)])

service = AiAssessmentService()


@router.post("/process", response_model=ProcessImagesResponse)
async def process_images(payload: ProcessImagesRequest, request: Request) -> ProcessImagesResponse:
    """Validate, downscale and strip metadata from images referenced by signed URL."""
    cid = payload.correlation_id or request.headers.get("X-Correlation-Id") or new_correlation_id()
    correlation_id.set(cid)
    _, infos, failures = await service.process_urls(
        [str(u) for u in payload.image_urls], cid, payload.generate_thumbnails)
    if not infos and failures:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY,
                            failures[0].get("reason", "No usable image was found"))
    return ProcessImagesResponse(images=infos, correlation_id=cid)


@router.post("/optimize", response_model=ProcessedImageInfo)
async def optimize(request: Request, file: UploadFile = File(...)) -> ProcessedImageInfo:
    """Optimise a directly uploaded file. Reports what changed rather than returning the bytes —
    the caller usually wants the numbers, and streaming an image back through Java would defeat the
    point of processing it here."""
    cid = request.headers.get("X-Correlation-Id") or new_correlation_id()
    correlation_id.set(cid)
    try:
        data = await file.read()
        result = image_processor.process(data, source=file.filename)
        return result.info
    except image_processor.ImageRejected as exc:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc
