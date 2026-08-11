"""The repair state machine.

An agent that decides its own next step by asking a model will eventually loop, skip the safety
check, or hand out instructions for a gas leak. This module makes the legal moves explicit: the
agent chooses *among permitted transitions*, and anything else is rejected before it can happen.

The rule that matters: no path reaches SAFE_DIY or REPAIR_PLAN_CREATED without passing through
SAFETY_CHECK. That is enforced structurally here, not by prompt wording.
"""

from __future__ import annotations

from typing import Dict, Set

import structlog

# The state enum is part of the wire contract, so it is defined with the response schemas and
# imported back here. Re-exported so `from app.services.state_machine import RepairState` — which
# reads naturally next to the transition table — keeps working.
from app.schemas.repair import RepairState

log = structlog.get_logger()

__all__ = ["RepairState", "TRANSITIONS", "IllegalTransition", "can_transition", "transition",
           "allows_repair_plan"]


# Every legal move. Absence is a rejection — this is the whole point of the module.
TRANSITIONS: Dict[RepairState, Set[RepairState]] = {
    RepairState.NEW: {
        RepairState.COLLECTING_INFORMATION, RepairState.SAFETY_CHECK, RepairState.EMERGENCY,
    },
    RepairState.COLLECTING_INFORMATION: {
        RepairState.COLLECTING_INFORMATION, RepairState.WAITING_FOR_IMAGE,
        RepairState.SAFETY_CHECK, RepairState.EMERGENCY, RepairState.CLOSED,
    },
    RepairState.WAITING_FOR_IMAGE: {
        RepairState.IMAGE_ANALYSIS, RepairState.COLLECTING_INFORMATION,
        RepairState.SAFETY_CHECK, RepairState.EMERGENCY, RepairState.CLOSED,
    },
    RepairState.IMAGE_ANALYSIS: {
        RepairState.SAFETY_CHECK, RepairState.WAITING_FOR_IMAGE,
        RepairState.COLLECTING_INFORMATION, RepairState.EMERGENCY,
    },
    # The gate. Every verdict leaves here, and nothing bypasses it.
    RepairState.SAFETY_CHECK: {
        RepairState.SAFE_DIY, RepairState.PROFESSIONAL_REQUIRED, RepairState.EMERGENCY,
        RepairState.INSUFFICIENT_INFORMATION,
    },
    RepairState.INSUFFICIENT_INFORMATION: {
        RepairState.COLLECTING_INFORMATION, RepairState.WAITING_FOR_IMAGE,
        RepairState.PROFESSIONAL_REQUIRED, RepairState.EMERGENCY, RepairState.CLOSED,
    },
    RepairState.SAFE_DIY: {
        RepairState.REPAIR_PLAN_CREATED,
        # A later turn can still reveal a hazard, so the escalating routes stay open.
        RepairState.SAFETY_CHECK, RepairState.PROFESSIONAL_REQUIRED, RepairState.EMERGENCY,
        RepairState.CLOSED,
    },
    RepairState.REPAIR_PLAN_CREATED: {
        RepairState.STEP_IN_PROGRESS, RepairState.PROFESSIONAL_REQUIRED,
        RepairState.EMERGENCY, RepairState.CLOSED,
    },
    RepairState.STEP_IN_PROGRESS: {
        RepairState.WAITING_FOR_VERIFICATION, RepairState.STEP_IN_PROGRESS,
        RepairState.REPAIR_COMPLETED, RepairState.PROFESSIONAL_REQUIRED,
        RepairState.EMERGENCY, RepairState.CLOSED,
    },
    RepairState.WAITING_FOR_VERIFICATION: {
        RepairState.STEP_VERIFICATION, RepairState.PROFESSIONAL_REQUIRED,
        RepairState.EMERGENCY, RepairState.CLOSED,
    },
    RepairState.STEP_VERIFICATION: {
        RepairState.STEP_IN_PROGRESS, RepairState.STEP_FAILED,
        RepairState.WAITING_FOR_VERIFICATION, RepairState.REPAIR_COMPLETED,
        RepairState.PROFESSIONAL_REQUIRED, RepairState.EMERGENCY,
    },
    RepairState.STEP_FAILED: {
        RepairState.STEP_IN_PROGRESS, RepairState.WAITING_FOR_VERIFICATION,
        RepairState.PROFESSIONAL_REQUIRED, RepairState.ESCALATED, RepairState.CLOSED,
    },
    RepairState.REPAIR_COMPLETED: {RepairState.CLOSED},
    RepairState.PROFESSIONAL_REQUIRED: {
        RepairState.CONTRACTOR_SEARCH, RepairState.ESCALATED, RepairState.CLOSED,
    },
    # Terminal for the assistant: an emergency never returns to a DIY path in the same repair.
    RepairState.EMERGENCY: {
        RepairState.CONTRACTOR_SEARCH, RepairState.ESCALATED, RepairState.CLOSED,
    },
    RepairState.CONTRACTOR_SEARCH: {
        RepairState.CONTRACTOR_REQUESTED, RepairState.ESCALATED, RepairState.CLOSED,
    },
    RepairState.CONTRACTOR_REQUESTED: {
        RepairState.CONTRACTOR_ACCEPTED, RepairState.CONTRACTOR_SEARCH, RepairState.CLOSED,
    },
    RepairState.CONTRACTOR_ACCEPTED: {RepairState.CLOSED},
    RepairState.ESCALATED: {RepairState.CONTRACTOR_SEARCH, RepairState.CLOSED},
    RepairState.CLOSED: set(),
}

# States from which a repair plan may never be produced, whatever anything else decides.
NO_DIY_STATES = {
    RepairState.EMERGENCY, RepairState.PROFESSIONAL_REQUIRED,
    RepairState.INSUFFICIENT_INFORMATION, RepairState.CONTRACTOR_SEARCH,
    RepairState.CONTRACTOR_REQUESTED, RepairState.CONTRACTOR_ACCEPTED, RepairState.ESCALATED,
}


class IllegalTransition(Exception):
    """Raised loudly rather than silently corrected — an agent trying to skip the safety check is a
    bug worth surfacing, not papering over."""


def can_transition(current: RepairState, target: RepairState) -> bool:
    return target in TRANSITIONS.get(current, set())


def transition(current: RepairState, target: RepairState) -> RepairState:
    if current == target:
        return current
    if not can_transition(current, target):
        raise IllegalTransition(f"{current.value} -> {target.value} is not a permitted transition")
    log.info("state_transition", **{"from": current.value, "to": target.value})
    return target


def allows_repair_plan(state: RepairState) -> bool:
    """A plan may only be built from SAFE_DIY or while already working through one."""
    if state in NO_DIY_STATES:
        return False
    return state in {RepairState.SAFE_DIY, RepairState.REPAIR_PLAN_CREATED,
                     RepairState.STEP_IN_PROGRESS, RepairState.STEP_VERIFICATION}


def is_terminal(state: RepairState) -> bool:
    return not TRANSITIONS.get(state)
