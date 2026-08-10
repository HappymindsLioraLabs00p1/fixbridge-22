"""Image validation, normalisation and metadata removal.

The privacy requirement drives the design. FixBridge deliberately withholds a customer's exact
address from a contractor until a job is authorised, but it *does* show them the job photos — and a
photo taken on a phone usually carries GPS coordinates in its EXIF block. Re-encoding through this
module is what stops those coordinates reaching a contractor.

Re-encoding also happens to be the main cost control: vision models bill by pixels, so a 12 MP photo
downscaled to 1600px costs a fraction of the original to analyse.
"""

from __future__ import annotations

import hashlib
import io
from dataclasses import dataclass
from typing import Optional, Tuple

from PIL import Image, ImageOps, UnidentifiedImageError

from app.core.config import get_settings
from app.schemas.image import ProcessedImageInfo

# Formats we accept. Anything else is rejected rather than guessed at.
ALLOWED_FORMATS = {"JPEG", "PNG", "WEBP", "HEIF", "HEIC"}

# Pillow refuses absurdly large images by default (decompression-bomb guard). Keep that protection
# but raise the ceiling enough for legitimate high-resolution phone photos.
Image.MAX_IMAGE_PIXELS = 80_000_000


class ImageRejected(Exception):
    """Raised when input is not a usable image. The message is safe to show a caller."""


@dataclass
class ProcessedImage:
    info: ProcessedImageInfo
    data: bytes
    thumbnail: Optional[bytes]


def content_hash(data: bytes) -> str:
    """Identity of the bytes, used to avoid paying for the same analysis twice."""
    return hashlib.sha256(data).hexdigest()


def _has_gps(image: Image.Image) -> bool:
    """True when the source carried GPS coordinates — recorded so the privacy control is auditable
    rather than merely assumed."""
    try:
        exif = image.getexif()
        if not exif:
            return False
        # 0x8825 is the GPSInfo IFD pointer.
        return 0x8825 in exif
    except Exception:
        return False


def process(data: bytes, source: Optional[str] = None,
            generate_thumbnail: bool = True) -> ProcessedImage:
    """Validate, normalise and strip metadata from a single image."""
    settings = get_settings()

    if not data:
        raise ImageRejected("The image is empty")
    original_bytes = len(data)
    if original_bytes > settings.max_upload_bytes:
        raise ImageRejected(
            f"Image is {original_bytes // 1024 // 1024} MB; the limit is "
            f"{settings.max_upload_bytes // 1024 // 1024} MB"
        )

    try:
        image = Image.open(io.BytesIO(data))
        image.verify()  # cheap structural check: catches truncated and corrupt files
        image = Image.open(io.BytesIO(data))  # verify() consumes the file, so reopen
    except (UnidentifiedImageError, OSError) as exc:
        raise ImageRejected("The file is not a readable image") from exc

    source_format = (image.format or "").upper()
    if source_format not in ALLOWED_FORMATS:
        raise ImageRejected(f"Unsupported image format: {source_format or 'unknown'}")

    had_gps = _has_gps(image)

    # Apply the EXIF orientation before discarding EXIF, otherwise photos taken in portrait come
    # out rotated once the metadata that described the rotation is gone.
    image = ImageOps.exif_transpose(image)

    # Flatten transparency onto white; JPEG has no alpha channel and a bare convert() turns
    # transparent regions black.
    if image.mode in ("RGBA", "LA", "P"):
        image = image.convert("RGBA")
        background = Image.new("RGB", image.size, (255, 255, 255))
        background.paste(image, mask=image.split()[-1])
        image = background
    elif image.mode != "RGB":
        image = image.convert("RGB")

    image.thumbnail((settings.max_dimension, settings.max_dimension), Image.LANCZOS)

    # Re-encoding into a fresh buffer is what actually removes the metadata: nothing is copied
    # across from the original, so EXIF, GPS and maker notes are all gone by construction.
    out = io.BytesIO()
    image.save(out, format="JPEG", quality=settings.jpeg_quality, optimize=True)
    processed = out.getvalue()

    thumb_bytes: Optional[bytes] = None
    if generate_thumbnail:
        thumb = image.copy()
        thumb.thumbnail((settings.thumbnail_dimension, settings.thumbnail_dimension), Image.LANCZOS)
        tout = io.BytesIO()
        thumb.save(tout, format="JPEG", quality=75, optimize=True)
        thumb_bytes = tout.getvalue()

    info = ProcessedImageInfo(
        source=source,
        content_hash=content_hash(processed),
        format="JPEG",
        width=image.width,
        height=image.height,
        original_bytes=original_bytes,
        processed_bytes=len(processed),
        exif_stripped=True,
        had_gps=had_gps,
        thumbnail_bytes=len(thumb_bytes) if thumb_bytes else None,
    )
    return ProcessedImage(info=info, data=processed, thumbnail=thumb_bytes)


def verify_metadata_removed(data: bytes) -> Tuple[bool, bool]:
    """Read back a processed image and report (has_exif, has_gps).

    Used by the tests: the privacy claim is worth checking against the actual output rather than
    trusting that re-encoding did what we expect.
    """
    try:
        image = Image.open(io.BytesIO(data))
        exif = image.getexif()
        return bool(exif), bool(exif and 0x8825 in exif)
    except Exception:
        return False, False
