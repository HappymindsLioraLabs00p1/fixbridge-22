"""The Java <-> Python contract. Pydantic validates it, so a model that drifts from the schema
produces a clear error here rather than corrupt data in FixBridge's database."""

from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field, HttpUrl

from app.schemas.image import ProcessedImageInfo


class Urgency(str, Enum):
    low = "low"
    medium = "medium"
    high = "high"
    emergency = "emergency"


class Complexity(str, Enum):
    simple = "simple"
    medium = "medium"
    complex = "complex"


class AnalyzeFromUrlRequest(BaseModel):
    description: str = Field(min_length=1, max_length=5000)
    image_urls: List[HttpUrl] = Field(default_factory=list)
    # Java sends its own request id so a single job can be traced across both services.
    correlation_id: Optional[str] = None


class AssessmentResponse(BaseModel):
    """Never raw model output — every field is validated before it leaves this service."""

    category: str
    urgency: Urgency
    recommended_trade: str
    complexity: Complexity
    confidence: float = Field(ge=0.0, le=1.0)
    professional_required: bool
    safe_diy_allowed: bool
    assessment: str
    safety_notes: List[str] = Field(default_factory=list)
    estimated_labor_hours_min: Optional[float] = Field(default=None, ge=0)
    estimated_labor_hours_max: Optional[float] = Field(default=None, ge=0)

    # Audit: FixBridge stores which provider and model produced an assessment.
    provider: str
    model: str

    # What the image pipeline did — proves EXIF was stripped and shows the cost saving.
    images: List[ProcessedImageInfo] = Field(default_factory=list)
    correlation_id: Optional[str] = None
    cached: bool = False
