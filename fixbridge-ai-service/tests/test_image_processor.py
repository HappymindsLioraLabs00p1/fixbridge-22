"""Image pipeline tests.

The GPS test is the important one: FixBridge withholds a customer's address from a contractor but
shows them the job photos, so metadata surviving the pipeline would silently defeat that control.
"""

import io

import piexif  # type: ignore
import pytest
from PIL import Image

from app.services import image_processor
from app.services.image_processor import ImageRejected


def make_image(width=2400, height=1800, fmt="JPEG", color=(120, 140, 160)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (width, height), color).save(buf, format=fmt)
    return buf.getvalue()


def make_image_with_gps() -> bytes:
    """A photo carrying GPS coordinates, as a phone camera produces."""
    buf = io.BytesIO()
    exif = {
        "0th": {piexif.ImageIFD.Make: b"TestPhone"},
        "GPS": {
            piexif.GPSIFD.GPSLatitudeRef: b"N",
            piexif.GPSIFD.GPSLatitude: ((40, 1), (45, 1), (0, 1)),      # ~New York
            piexif.GPSIFD.GPSLongitudeRef: b"W",
            piexif.GPSIFD.GPSLongitude: ((73, 1), (59, 1), (0, 1)),
        },
        "Exif": {}, "1st": {}, "thumbnail": None,
    }
    Image.new("RGB", (1200, 900), (200, 100, 100)).save(
        buf, format="JPEG", exif=piexif.dump(exif))
    return buf.getvalue()


def test_accepts_a_normal_photo():
    result = image_processor.process(make_image())
    assert result.info.format == "JPEG"
    assert result.info.processed_bytes > 0


def test_downscales_to_the_configured_maximum():
    result = image_processor.process(make_image(4000, 3000))
    assert max(result.info.width, result.info.height) <= 1600


def test_compresses_substantially():
    result = image_processor.process(make_image(4000, 3000))
    assert result.info.processed_bytes < result.info.original_bytes


def test_gps_coordinates_are_removed():
    """The privacy control: a photo with GPS must come out with none."""
    original = make_image_with_gps()
    before_exif, before_gps = image_processor.verify_metadata_removed(original)
    assert before_gps, "fixture should carry GPS to make this test meaningful"

    result = image_processor.process(original)

    assert result.info.had_gps is True          # recorded, so the removal is auditable
    assert result.info.exif_stripped is True
    _, after_gps = image_processor.verify_metadata_removed(result.data)
    assert after_gps is False, "GPS coordinates survived processing — customer address would leak"


def test_generates_a_thumbnail():
    result = image_processor.process(make_image(), generate_thumbnail=True)
    assert result.thumbnail and len(result.thumbnail) < result.info.processed_bytes


def test_rejects_a_corrupt_file():
    with pytest.raises(ImageRejected):
        image_processor.process(b"this is definitely not an image")


def test_rejects_an_empty_file():
    with pytest.raises(ImageRejected):
        image_processor.process(b"")


def test_rejects_an_oversized_file(monkeypatch):
    from app.core.config import get_settings
    settings = get_settings()
    monkeypatch.setattr(settings, "max_upload_bytes", 1024)
    with pytest.raises(ImageRejected, match="limit"):
        image_processor.process(make_image())


def test_transparency_is_flattened_not_blackened():
    buf = io.BytesIO()
    Image.new("RGBA", (600, 400), (255, 255, 255, 0)).save(buf, format="PNG")
    result = image_processor.process(buf.getvalue())
    pixel = Image.open(io.BytesIO(result.data)).convert("RGB").getpixel((10, 10))
    assert all(channel > 200 for channel in pixel), "transparent area should flatten to white"


def test_identical_bytes_hash_identically():
    data = make_image()
    a = image_processor.process(data)
    b = image_processor.process(data)
    assert a.info.content_hash == b.info.content_hash
