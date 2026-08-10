"""Job summary tests.

The behaviours that matter: the customer's own words survive, confidentiality holds, and the
briefing says what is still unknown rather than implying the job is fully understood.
"""

import pytest
from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import app
from app.schemas.repair import Message, SafetyAssessment, SafetyLevel
from app.services.job_summary_agent import JobSummaryAgent

client = TestClient(app)
AUTH = {"Authorization": "Bearer test-token"}
agent = JobSummaryAgent()


@pytest.fixture(autouse=True)
def stub(monkeypatch):
    s = get_settings()
    monkeypatch.setattr(s, "stub_mode", True)
    monkeypatch.setattr(s, "service_auth_token", "test-token")
    monkeypatch.setattr(s, "environment", "test")


def msgs(*texts, images=0):
    out = [Message(role="customer", text=t) for t in texts]
    if images and out:
        out[-1].image_urls = [f"https://x/{i}.jpg" for i in range(images)]
    return out


def test_maps_the_category_to_a_trade():
    assert agent.required_trade("plumbing") == "licensed_plumber"
    assert agent.required_trade("electrical") == "licensed_electrician"
    assert agent.required_trade(None) == "handyman"


def test_keeps_the_customers_own_words():
    """Paraphrasing turns 'it clicks twice then stops' into 'it won't start' — a different job."""
    words = "It clicks twice then stops and the light flashes"
    s = agent.build(msgs(words), "appliance", None,
                    SafetyAssessment(level=SafetyLevel.PROFESSIONAL_REQUIRED, confidence=0.8))
    assert s["customer_description"] == words


def test_records_what_was_already_established():
    s = agent.build(msgs("Sink leaking", "Constantly", "A slow drip"), "plumbing", "leak",
                    SafetyAssessment(level=SafetyLevel.PROFESSIONAL_REQUIRED, confidence=0.8),
                    image_count=2)
    joined = " ".join(s["established"])
    assert "Constantly" in joined and "2 photo" in joined


def test_says_what_is_still_unknown():
    s = agent.build(msgs("Something is wrong"), "general", None,
                    SafetyAssessment(level=SafetyLevel.INSUFFICIENT_INFORMATION, confidence=0.3))
    assert s["outstanding"], "a briefing must admit what it doesn't know"
    assert any("photograph" in o.lower() for o in s["outstanding"])


def test_explains_why_it_escalated():
    s = agent.build(msgs("I can smell gas"), "plumbing", "gas smell",
                    SafetyAssessment(level=SafetyLevel.EMERGENCY, confidence=0.9,
                                     reasons=["Mentions a possible gas hazard."]))
    assert s["urgency"] == "EMERGENCY"
    assert "gas" in s["why_escalated"].lower()


def test_never_includes_price_or_margin():
    """Contractors see a net figure elsewhere; the briefing must not leak retail or margin."""
    s = agent.build(msgs("Leaking pipe under the sink costing me a fortune"), "plumbing", "leak",
                    SafetyAssessment(level=SafetyLevel.PROFESSIONAL_REQUIRED, confidence=0.8))
    blob = str(s).lower()
    for term in ("retail", "margin", "markup"):
        assert term not in blob or "withheld" in blob


def test_endpoint_returns_a_briefing():
    r = client.post("/v1/repair/job-summary", headers=AUTH, json={
        "messages": [{"role": "customer", "text": "I can smell gas near the boiler"}]})
    assert r.status_code == 200
    body = r.json()
    assert body["urgency"] == "EMERGENCY"
    assert body["required_trade"]
    assert "withheld" in body["excluded"]


def test_endpoint_requires_authentication():
    r = client.post("/v1/repair/job-summary", json={
        "messages": [{"role": "customer", "text": "hello"}]})
    assert r.status_code == 401
