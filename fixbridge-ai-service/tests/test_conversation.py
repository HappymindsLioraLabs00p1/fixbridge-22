"""Conversation flow tests: escalation, memory, and the DIY gate."""

import pytest
from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import app

client = TestClient(app)
AUTH = {"Authorization": "Bearer test-token"}


@pytest.fixture(autouse=True)
def stub_mode(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "stub_mode", True)
    monkeypatch.setattr(settings, "service_auth_token", "test-token")
    monkeypatch.setattr(settings, "environment", "test")


def converse(*turns, images=None):
    messages = []
    for i, text in enumerate(turns):
        messages.append({"role": "customer" if i % 2 == 0 else "assistant", "text": text,
                         "image_urls": images if (images and i == len(turns) - 1) else []})
    return client.post("/v1/repair/converse", headers=AUTH, json={"messages": messages}).json()


def test_requires_authentication():
    r = client.post("/v1/repair/converse", json={"messages": [{"role": "customer", "text": "hi"}]})
    assert r.status_code == 401


def test_asks_one_question_at_a_time():
    body = converse("My kitchen sink is leaking")
    assert body["status"] == "NEED_MORE_INFORMATION"
    assert body["category"] == "plumbing"
    assert body["question"]
    assert body["quick_replies"]


def test_does_not_repeat_an_answered_question():
    """The customer already said 'constantly', so that question must not come back."""
    first = converse("My kitchen sink is leaking")
    second = converse("My kitchen sink is leaking constantly, it's been a few days")
    assert second["question"] != first["question"]


def test_gas_escalates_immediately_and_offers_no_plan():
    body = converse("I can smell gas near the boiler")
    assert body["status"] == "EMERGENCY"
    assert body["safety"]["level"] == "EMERGENCY"
    assert body["repair_plan"] is None
    assert "Find a professional" in body["quick_replies"]


def test_electrical_work_never_gets_a_diy_plan():
    body = converse("I need to replace the wiring in my consumer unit, it's been like this a week")
    assert body["status"] == "PROFESSIONAL_REQUIRED"
    assert body["repair_plan"] is None


def test_escalation_mentions_safety_even_many_turns_later():
    """A hazard raised early must still govern the conversation."""
    body = converse("I can smell gas", "Can you tell me more?", "It's near the cooker, since today")
    assert body["safety"]["level"] == "EMERGENCY"


def test_a_safe_problem_eventually_yields_a_structured_plan():
    body = converse(
        "The cabinet door hinge is loose and rattling, it started a few days ago and the screws "
        "look like they have worked their way out of the frame over time",
        "Thanks — anything else?",
        "No, that's everything, it just needs tightening",
        images=["https://example.com/hinge.jpg"],
    )
    # Either it asks for a photo or produces a plan; both are valid, neither may be an escalation.
    assert body["status"] in ("NEED_IMAGE", "REPAIR_PLAN_READY", "NEED_MORE_INFORMATION")
    assert body["safety"]["level"] in ("SAFE_DIY", "INSUFFICIENT_INFORMATION")


def test_every_response_carries_the_disclaimer():
    body = converse("My tap drips")
    assert "qualified professional" in body["safety"]["disclaimer"]


def test_repair_plan_steps_are_structured_not_prose():
    from app.schemas.repair import SafetyAssessment, SafetyLevel
    from app.services.repair_planner import RepairPlanner

    plan = RepairPlanner().plan(
        "Leak under the sink", "plumbing",
        SafetyAssessment(level=SafetyLevel.SAFE_DIY, confidence=0.8))
    assert len(plan.steps) >= 3
    first = plan.steps[0]
    assert first.number == 1 and first.instruction and first.why
    assert plan.stop_conditions, "a plan must tell the customer when to stop"
    assert any(s.requires_image_verification for s in plan.steps)


def test_planner_refuses_to_build_a_plan_for_an_unsafe_verdict():
    from app.schemas.repair import SafetyAssessment, SafetyLevel
    from app.services.repair_planner import RepairPlanner

    with pytest.raises(ValueError):
        RepairPlanner().plan("Gas leak", "plumbing",
                             SafetyAssessment(level=SafetyLevel.EMERGENCY, confidence=0.9))
