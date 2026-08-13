"""The traffic light shown to a homeowner.

GREEN, YELLOW and RED are presentation, but presenting the wrong one is a safety failure: someone
standing next to a gas leak reads the colour before the words. These assert the light can never
disagree with the verdict behind it.
"""

import pytest

from app.schemas.repair import (
    RISK_FOR_SAFETY,
    RiskLevel,
    SafetyAssessment,
    SafetyLevel,
    risk_for,
)


def assessment(level: SafetyLevel) -> SafetyAssessment:
    return SafetyAssessment(level=level, confidence=0.8)


@pytest.mark.parametrize(
    "level,expected",
    [
        (SafetyLevel.SAFE_DIY, RiskLevel.GREEN),
        (SafetyLevel.INSUFFICIENT_INFORMATION, RiskLevel.YELLOW),
        (SafetyLevel.PROFESSIONAL_REQUIRED, RiskLevel.RED),
        (SafetyLevel.EMERGENCY, RiskLevel.RED),
    ],
)
def test_each_verdict_shows_the_right_light(level, expected):
    assert risk_for(level) is expected
    assert assessment(level).risk is expected


def test_every_verdict_is_mapped():
    """A verdict added later must not fall through to a default nobody thought about."""
    for level in SafetyLevel:
        assert level in RISK_FOR_SAFETY, f"{level} has no risk level"


def test_an_unrecognised_verdict_is_red_not_green():
    """Fail safe. An unknown verdict presenting as GREEN would invite a DIY attempt on a job
    nothing has cleared."""
    assert risk_for("SOMETHING_NEW_ENTIRELY") is RiskLevel.RED  # type: ignore[arg-type]


def test_only_a_safe_verdict_is_ever_green():
    greens = [lv for lv in SafetyLevel if risk_for(lv) is RiskLevel.GREEN]
    assert greens == [SafetyLevel.SAFE_DIY]


def test_both_escalating_verdicts_are_red():
    assert risk_for(SafetyLevel.EMERGENCY) is RiskLevel.RED
    assert risk_for(SafetyLevel.PROFESSIONAL_REQUIRED) is RiskLevel.RED


def test_the_light_is_serialised_for_clients():
    """It has to reach the browser, or the UI recomputes it and the two drift apart."""
    assert assessment(SafetyLevel.EMERGENCY).model_dump()["risk"] is RiskLevel.RED


def test_the_light_cannot_be_set_independently_of_the_verdict():
    """A response claiming GREEN beside an EMERGENCY must be impossible to construct."""
    a = SafetyAssessment(level=SafetyLevel.EMERGENCY, confidence=0.9, risk=RiskLevel.GREEN)  # type: ignore[call-arg]
    assert a.risk is RiskLevel.RED
