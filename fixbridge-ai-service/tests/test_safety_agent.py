"""Safety gate tests.

These are the highest-stakes tests in the codebase: a false SAFE_DIY is how someone gets hurt. Each
case asserts that a real-world hazard escalates regardless of anything a model might say.
"""

import pytest

from app.schemas.repair import SafetyLevel
from app.services.safety_agent import SafetyAgent

agent = SafetyAgent()


@pytest.mark.parametrize("text", [
    "I can smell gas near the boiler",
    "There's a carbon monoxide alarm going off",
    "The outlet sparked and now there's smoke",
    "I think there's a fire behind the wall",
    "Water is pouring out and flooding the kitchen",
    "There's sewage backing up into the bath",
])
def test_life_threatening_situations_are_emergencies(text):
    assert agent.assess(text, confidence=0.9).level == SafetyLevel.EMERGENCY


@pytest.mark.parametrize("text", [
    "I need to replace some wiring in the consumer unit",
    "The circuit breaker keeps tripping",
    "My boiler needs the flue checked",
    "There's a major crack in a load-bearing wall",
    "A tile came off the roof and I need to go up a ladder",
    "I think there's asbestos in the ceiling",
])
def test_professional_only_work_escalates(text):
    assert agent.assess(text, confidence=0.9).level == SafetyLevel.PROFESSIONAL_REQUIRED


def test_a_simple_problem_is_allowed_when_confidence_is_adequate():
    a = agent.assess("The cabinet door hinge is loose and rattles", confidence=0.8)
    assert a.level == SafetyLevel.SAFE_DIY
    assert agent.allows_diy(a)


def test_low_confidence_is_not_treated_as_safe():
    """Not knowing is its own answer — it must not collapse into SAFE_DIY."""
    a = agent.assess("Something is wrong", confidence=0.2)
    assert a.level == SafetyLevel.INSUFFICIENT_INFORMATION
    assert not agent.allows_diy(a)


def test_a_model_may_escalate_but_never_relax_the_verdict():
    hazardous = "I can smell gas"
    # Even told the model thinks it's fine, the rule wins.
    a = agent.assess(hazardous, confidence=0.99, model_opinion=SafetyLevel.SAFE_DIY)
    assert a.level == SafetyLevel.EMERGENCY

    # And a model may make a safe verdict stricter.
    b = agent.assess("A loose hinge", confidence=0.9,
                     model_opinion=SafetyLevel.PROFESSIONAL_REQUIRED)
    assert b.level == SafetyLevel.PROFESSIONAL_REQUIRED


def test_the_strictest_hazard_wins_when_several_are_present():
    a = agent.assess("There's a crack in the wall and I can smell gas", confidence=0.9)
    assert a.level == SafetyLevel.EMERGENCY


def test_every_assessment_carries_the_disclaimer():
    a = agent.assess("A dripping tap", confidence=0.8)
    assert "qualified professional" in a.disclaimer


def test_hazards_are_named_so_the_reason_is_explainable():
    a = agent.assess("I can smell gas near the boiler", confidence=0.9)
    assert a.hazards and a.reasons
