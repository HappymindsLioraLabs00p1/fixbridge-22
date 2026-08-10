"""Image processing contract."""

from typing import List, Optional

from pydantic import BaseModel, Field, HttpUrl


class ProcessedImageInfo(BaseModel):
    """What happened to one image. `exif_stripped` is the field that matters: FixBridge shows job
    photos to contractors before releasing the customer's address, and phone photos carry GPS
    coordinates, so metadata removal is a privacy control rather than an optimisation."""

    source: Optional[str] = None
    content_hash: str
    format: str
    width: int
    height: int
    original_bytes: int
    processed_bytes: int
    exif_stripped: bool
    had_gps: bool = False
    thumbnail_bytes: Optional[int] = None

    @property
    def reduction_percent(self) -> float:
        if not self.original_bytes:
            return 0.0
        return round((1 - self.processed_bytes / self.original_bytes) * 100, 1)


class ProcessImagesRequest(BaseModel):
    image_urls: List[HttpUrl] = Field(default_factory=list)
    correlation_id: Optional[str] = None
    generate_thumbnails: bool = True


class ProcessImagesResponse(BaseModel):
    images: List[ProcessedImageInfo]
    correlation_id: Optional[str] = None
