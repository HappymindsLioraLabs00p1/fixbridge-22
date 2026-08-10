"""State machine tests.

These exist to prove structurally what a prompt can only ask for politely: that nothing reaches a
DIY instruction without passing the safety gate, and that an emergency cannot be walked back into a
repair plan.
"""

import pytest

from app.services.state_machine import (
    IllegalTransition,
    RepairState,
    TRANSITIONS,
    allows_repair_plan,
    can_transition,
    is_terminal,
    transition,
)


def test_a_new_repair_starts_by_collecting_information():
    assert can_transition(RepairState.NEW, RepairState.COLLECTING_INFORMATION)


def test_safety_check_is_unavoidable_before_diy():
    """The central guarantee. No state reaches SAFE_DIY except through SAFETY_CHECK."""
    for state, targets in TRANSITIONS.items():
        if RepairState.SAFE_DIY in targets:
            assert state == RepairState.SAFETY_CHECK, (
                f"{state.value} can reach SAFE_DIY without a safety check")


def test_a_plan_cannot_be_produced_before_the_safety_verdict():
    for state in (RepairState.NEW, RepairState.COLLECTING_INFORMATION,
                  RepairState.WAITING_FOR_IMAGE, RepairState.IMAGE_ANALYSIS,
                  RepairState.SAFETY_CHECK):
        assert not allows_repair_plan(state)


def test_a_plan_is_permitted_once_the_verdict_is_safe():
    assert allows_repair_plan(RepairState.SAFE_DIY)
    assert allows_repair_plan(RepairState.STEP_IN_PROGRESS)


@pytest.mark.parametrize("state", [
    RepairState.EMERGENCY,
    RepairState.PROFESSIONAL_REQUIRED,
    RepairState.INSUFFICIENT_INFORMATION,
    RepairState.ESCALATED,
    RepairState.CONTRACTOR_REQUESTED,
])
def test_no_plan_is_ever_produced_from_an_escalated_state(state):
    assert not allows_repair_plan(state)


def test_an_emergency_cannot_return_to_a_diy_path():
    """Once it's an emergency it stays escalated for this repair."""
    for target in (RepairState.SAFE_DIY, RepairState.REPAIR_PLAN_CREATED,
                   RepairState.STEP_IN_PROGRESS, RepairState.COLLECTING_INFORMATION):
        assert not can_transition(RepairState.EMERGENCY, target)


def test_an_emergency_may_only_move_toward_a_professional_or_closure():
    assert can_transition(RepairState.EMERGENCY, RepairState.CONTRACTOR_SEARCH)
    assert can_transition(RepairState.EMERGENCY, RepairState.CLOSED)


def test_a_hazard_found_later_can_still_escalate_a_safe_repair():
    """A conversation that looked safe may reveal a hazard three turns in."""
    assert can_transition(RepairState.SAFE_DIY, RepairState.EMERGENCY)
    assert can_transition(RepairState.STEP_IN_PROGRESS, RepairState.PROFESSIONAL_REQUIRED)


def test_an_illegal_transition_raises_rather_than_being_corrected():
    with pytest.raises(IllegalTransition):
        transition(RepairState.NEW, RepairState.REPAIR_PLAN_CREATED)


def test_transitioning_to_the_same_state_is_allowed():
    assert transition(RepairState.COLLECTING_INFORMATION,
                      RepairState.COLLECTING_INFORMATION) == RepairState.COLLECTING_INFORMATION


def test_a_legal_transition_returns_the_new_state():
    assert transition(RepairState.SAFETY_CHECK, RepairState.SAFE_DIY) == RepairState.SAFE_DIY


def test_closed_is_terminal():
    assert is_terminal(RepairState.CLOSED)
    assert not is_terminal(RepairState.NEW)


def test_verification_failure_can_escalate_rather_than_looping_forever():
    assert can_transition(RepairState.STEP_FAILED, RepairState.PROFESSIONAL_REQUIRED)
    assert can_transition(RepairState.STEP_FAILED, RepairState.ESCALATED)


def test_every_state_is_reachable_from_new():
    """A state nothing can reach is dead code pretending to be a feature."""
    seen, frontier = {RepairState.NEW}, [RepairState.NEW]
    while frontier:
        for nxt in TRANSITIONS.get(frontier.pop(), set()):
            if nxt not in seen:
                seen.add(nxt)
                frontier.append(nxt)
    unreachable = set(RepairState) - seen
    assert not unreachable, f"unreachable states: {[s.value for s in unreachable]}"
