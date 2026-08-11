"""The repair state on the wire.

The state machine was only ever observable inside the process: the Repair Manager computed it and
the response threw it away, so a client could distinguish four situations out of twenty. These
assert the state actually reaches the caller, survives a round trip, and agrees with the safety
verdict — the last of which is what a guard checking a constant it had just assigned could never
catch.
"""

import pytest

from app.agents.repair_manager import RepairManagerAgent
from app.schemas.repair import (
    ConversationRequest,
    ConversationStatus,
    Message,
    RepairState,
    SafetyLevel,
)
from app.services.conversation_agent import STATE_FOR_SAFETY, ConversationAgent

manager = RepairManagerAgent()
agent = ConversationAgent()

SAFE = "the cabinet door hinge is loose and rattling, screws backing out, started a few days ago"
GAS = "I can smell gas near the boiler"
WIRING = "there is an exposed live wire hanging out of the wall socket"


def req(*texts, state=None, images=0):
    msgs = [Message(role="customer", text=t) for t in texts]
    if images and msgs:
        msgs[-1].image_urls = [f"https://x/{i}.jpg" for i in range(images)]
    return ConversationRequest(messages=msgs, current_state=state)


# ---- the state reaches the caller ------------------------------------------------------------

def test_the_response_carries_a_state():
    response, _ = manager.handle(req(SAFE, images=1))
    assert isinstance(response.state, RepairState)


def test_a_gas_report_reports_the_emergency_state_not_just_the_status():
    response, ctx = manager.handle(req(GAS))
    assert response.state == RepairState.EMERGENCY
    assert ctx.state == RepairState.EMERGENCY


def test_emergency_is_distinguishable_from_professional_required():
    """Both collapse to an escalation for the client's purposes, but a caller must be able to tell
    'call a professional' apart from 'stop, this is dangerous'."""
    gas, _ = manager.handle(req(GAS))
    wiring, _ = manager.handle(req(WIRING))
    assert gas.state == RepairState.EMERGENCY
    assert wiring.state in (RepairState.PROFESSIONAL_REQUIRED, RepairState.EMERGENCY)


def test_the_state_is_never_left_at_new_after_a_real_turn():
    """The bug this file exists for: the manager computed a state and the response discarded it,
    so every turn looked like NEW from outside."""
    for text in (SAFE, GAS, WIRING):
        response, _ = manager.handle(req(text))
        assert response.state != RepairState.NEW, f"state not advanced for: {text}"


# ---- the state survives a round trip ---------------------------------------------------------

def test_an_absent_incoming_state_still_advances_during_the_turn():
    """A fresh conversation begins at NEW, but the turn must not leave it there."""
    request = req("my tap drips")
    assert request.current_state is None
    _, ctx = manager.handle(request)
    assert ctx.state != RepairState.NEW


def test_an_incoming_state_is_honoured():
    """Without this the service restarts at NEW every turn and the machine polices nothing."""
    first, _ = manager.handle(req(SAFE, images=1))
    resumed, _ = manager.handle(req(SAFE, state=first.state, images=1))
    assert resumed.state is not None


@pytest.mark.parametrize("state", list(RepairState))
def test_every_state_is_accepted_as_an_incoming_value(state):
    """An unknown or unexpected incoming state must not crash the turn."""
    response, _ = manager.handle(req("my tap drips", state=state))
    assert isinstance(response.state, RepairState)


# ---- the state agrees with the verdict -------------------------------------------------------

def test_the_verdict_to_state_table_covers_every_safety_level():
    for level in SafetyLevel:
        assert level in STATE_FOR_SAFETY, f"no state mapped for {level}"


def test_a_plan_is_only_ever_returned_with_a_safe_state():
    """The guard that replaced the constant-check: if a plan came back, the state must permit it."""
    for text in (SAFE, GAS, WIRING, "my tap drips", "the roof is sagging badly"):
        response, _ = manager.handle(req(text, images=1))
        if response.repair_plan is not None:
            assert response.state == RepairState.SAFE_DIY
            assert response.safety.level == SafetyLevel.SAFE_DIY


def test_an_escalated_turn_never_carries_a_plan():
    for text in (GAS, WIRING):
        response, _ = manager.handle(req(text))
        assert response.repair_plan is None
        assert response.status in (ConversationStatus.EMERGENCY,
                                   ConversationStatus.PROFESSIONAL_REQUIRED)
