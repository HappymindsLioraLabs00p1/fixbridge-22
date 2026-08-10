"""API tests: auth, validation, safety rules and the response contract.

No test calls a real model — the vision layer runs in stub mode, so the suite is free and
deterministic.
"""

import io

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.core.config import get_settings
from app.main import app

client = TestClient(app)


@pytest.fixture(autouse=True)
def stub_mode(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "stub_mode", True)
    monkeypatch.setattr(settings, "service_auth_token", "test-token")
    monkeypatch.setattr(settings, "environment", "test")
    return settings


AUTH = {"Authorization": "Bearer test-token"}


def image_bytes() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (900, 700), (100, 120, 140)).save(buf, format="JPEG")
    return buf.getvalue()


# ---- health -------------------------------------------------------------------------------

def test_health_needs_no_auth():
    assert client.get("/health").status_code == 200


def test_ready_reports_configuration():
    body = client.get("/ready").json()
    assert body["status"] == "ready"
    assert "max_images_per_assessment" in body["limits"]


# ---- authentication -----------------------------------------------------------------------

def test_assessment_requires_a_token():
    r = client.post("/v1/assessment/analyze-from-url",
                    json={"description": "leaking pipe", "image_urls": []})
    assert r.status_code == 401


def test_assessment_rejects_a_wrong_token():
    r = client.post("/v1/assessment/analyze-from-url",
                    headers={"Authorization": "Bearer wrong"},
                    json={"description": "leaking pipe", "image_urls": []})
    assert r.status_code == 401


# ---- contract -----------------------------------------------------------------------------

def test_returns_the_agreed_shape():
    r = client.post("/v1/assessment/analyze-from-url", headers=AUTH,
                    json={"description": "Water leaking from the pipe under the kitchen sink"})
    assert r.status_code == 200
    body = r.json()
    for field in ("category", "urgency", "recommended_trade", "complexity", "confidence",
                  "professional_required", "safe_diy_allowed", "assessment", "safety_notes",
                  "provider", "model"):
        assert field in body, f"missing contract field: {field}"
    assert 0.0 <= body["confidence"] <= 1.0


def test_plumbing_is_classified_as_plumbing():
    r = client.post("/v1/assessment/analyze-from-url", headers=AUTH,
                    json={"description": "Water leaking from a pipe under the sink"})
    assert r.json()["category"] == "plumbing"


def test_empty_description_is_rejected():
    r = client.post("/v1/assessment/analyze-from-url", headers=AUTH,
                    json={"description": "", "image_urls": []})
    assert r.status_code == 422


# ---- safety -------------------------------------------------------------------------------

def test_gas_is_never_diy():
    r = client.post("/v1/assessment/analyze-from-url", headers=AUTH,
                    json={"description": "Strong smell of gas near the boiler"})
    body = r.json()
    assert body["safe_diy_allowed"] is False
    assert body["professional_required"] is True
    assert body["urgency"] == "emergency"


def test_electrical_sparking_is_never_diy():
    r = client.post("/v1/assessment/analyze-from-url", headers=AUTH,
                    json={"description": "The outlet sparked and there is a burning smell"})
    body = r.json()
    assert body["safe_diy_allowed"] is False
    assert body["professional_required"] is True


def test_no_price_is_ever_returned():
    """Pricing belongs to the Java engine. An AI-set price would be unauditable."""
    r = client.post("/v1/assessment/analyze-from-url", headers=AUTH,
                    json={"description": "Water leaking under the sink"})
    text = r.text.lower()
    for term in ("price", "cost_estimate", "retail", "quote", "usd", "dollar"):
        assert term not in text, f"the assessment leaked pricing language: {term}"


# ---- images -------------------------------------------------------------------------------

def test_optimize_reports_the_saving():
    r = client.post("/v1/images/optimize", headers=AUTH,
                    files={"file": ("photo.jpg", image_bytes(), "image/jpeg")})
    assert r.status_code == 200
    body = r.json()
    assert body["exif_stripped"] is True
    assert body["processed_bytes"] > 0


def test_optimize_rejects_a_non_image():
    r = client.post("/v1/images/optimize", headers=AUTH,
                    files={"file": ("notes.txt", b"just text", "text/plain")})
    assert r.status_code == 422


def test_correlation_id_is_echoed():
    r = client.post("/v1/assessment/analyze-from-url",
                    headers={**AUTH, "X-Correlation-Id": "FIX-123456"},
                    json={"description": "Blocked drain"})
    assert r.headers.get("X-Correlation-Id") == "FIX-123456"
    assert r.json()["correlation_id"] == "FIX-123456"


def test_repeat_requests_are_served_from_cache():
    payload = {"description": "Identical description for the cache test"}
    first = client.post("/v1/assessment/analyze-from-url", headers=AUTH, json=payload).json()
    second = client.post("/v1/assessment/analyze-from-url", headers=AUTH, json=payload).json()
    assert first["cached"] is False
    assert second["cached"] is True
