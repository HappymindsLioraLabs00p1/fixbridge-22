"""Conversation, safety and repair-plan contracts.

Everything the frontend consumes is a validated object, never raw model text — a chat UI that
parses prose is a UI that breaks whenever the model rephrases itself.
"""

from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field, computed_field


class SafetyLevel(str, Enum):
    """The four outcomes the safety gate is allowed to produce."""

    SAFE_DIY = "SAFE_DIY"
    PROFESSIONAL_REQUIRED = "PROFESSIONAL_REQUIRED"
    EMERGENCY = "EMERGENCY"
    INSUFFICIENT_INFORMATION = "INSUFFICIENT_INFORMATION"


class RiskLevel(str, Enum):
    """How the safety verdict is shown to a homeowner.

    A traffic light is understood at a glance in a way that PROFESSIONAL_REQUIRED is not, which
    matters when the person reading it is standing next to the problem.

    Presentation only. It is derived from the verdict rather than decided separately, so there is
    no second opinion that could disagree with the gate — the verdict remains the single authority
    on whether a repair plan may exist.
    """

    GREEN = "GREEN"    # safe to guide
    YELLOW = "YELLOW"  # not enough known to say it is safe
    RED = "RED"        # a professional, or an emergency


#: The verdict a homeowner sees, from the verdict the gate reached. Anything not listed is treated
#: as RED: an unrecognised verdict must never present as safe.
RISK_FOR_SAFETY: dict[SafetyLevel, RiskLevel] = {
    SafetyLevel.SAFE_DIY: RiskLevel.GREEN,
    SafetyLevel.INSUFFICIENT_INFORMATION: RiskLevel.YELLOW,
    SafetyLevel.PROFESSIONAL_REQUIRED: RiskLevel.RED,
    SafetyLevel.EMERGENCY: RiskLevel.RED,
}


def risk_for(level: SafetyLevel) -> RiskLevel:
    """Fails safe: an unknown verdict is RED, never GREEN."""
    return RISK_FOR_SAFETY.get(level, RiskLevel.RED)


class RepairState(str, Enum):
    """Where a repair actually is.

    This lives here rather than beside the transition table because it is part of the wire
    contract: the client renders from it, so it belongs with the other response enums. The state
    machine imports it back.
    """

    NEW = "NEW"
    COLLECTING_INFORMATION = "COLLECTING_INFORMATION"
    WAITING_FOR_IMAGE = "WAITING_FOR_IMAGE"
    IMAGE_ANALYSIS = "IMAGE_ANALYSIS"
    SAFETY_CHECK = "SAFETY_CHECK"
    INSUFFICIENT_INFORMATION = "INSUFFICIENT_INFORMATION"
    SAFE_DIY = "SAFE_DIY"
    PROFESSIONAL_REQUIRED = "PROFESSIONAL_REQUIRED"
    EMERGENCY = "EMERGENCY"
    REPAIR_PLAN_CREATED = "REPAIR_PLAN_CREATED"
    STEP_IN_PROGRESS = "STEP_IN_PROGRESS"
    WAITING_FOR_VERIFICATION = "WAITING_FOR_VERIFICATION"
    STEP_VERIFICATION = "STEP_VERIFICATION"
    STEP_FAILED = "STEP_FAILED"
    REPAIR_COMPLETED = "REPAIR_COMPLETED"
    CONTRACTOR_SEARCH = "CONTRACTOR_SEARCH"
    CONTRACTOR_REQUESTED = "CONTRACTOR_REQUESTED"
    CONTRACTOR_ACCEPTED = "CONTRACTOR_ACCEPTED"
    ESCALATED = "ESCALATED"
    CLOSED = "CLOSED"


class ConversationStatus(str, Enum):
    NEED_MORE_INFORMATION = "NEED_MORE_INFORMATION"
    NEED_IMAGE = "NEED_IMAGE"
    REPAIR_PLAN_READY = "REPAIR_PLAN_READY"
    PROFESSIONAL_REQUIRED = "PROFESSIONAL_REQUIRED"
    EMERGENCY = "EMERGENCY"


class VerificationResult(str, Enum):
    STEP_COMPLETED = "STEP_COMPLETED"
    STEP_NOT_COMPLETED = "STEP_NOT_COMPLETED"
    UNCERTAIN = "UNCERTAIN"
    ESCALATE = "ESCALATE"


DISCLAIMER = ("AI assistance only. Final assessment must be performed by a qualified professional.")


class Message(BaseModel):
    """One turn. The caller replays the history, so this service stays stateless."""

    role: str = Field(pattern="^(customer|assistant)$")
    text: str
    image_urls: List[str] = Field(default_factory=list)


class SafetyAssessment(BaseModel):
    level: SafetyLevel
    reasons: List[str] = Field(default_factory=list)
    hazards: List[str] = Field(default_factory=list)
    confidence: float = Field(ge=0.0, le=1.0)
    disclaimer: str = DISCLAIMER

    @computed_field  # type: ignore[prop-decorator]
    @property
    def risk(self) -> RiskLevel:
        """The traffic light for this verdict.

        Computed, so it is serialised for clients but can never be set independently of the verdict
        it describes. A settable field would allow a response claiming GREEN beside an EMERGENCY.
        """
        return risk_for(self.level)


class RepairStep(BaseModel):
    number: int = Field(ge=1)
    instruction: str
    why: Optional[str] = None
    tools: List[str] = Field(default_factory=list)
    parts: List[str] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)
    expected_result: Optional[str] = None
    requires_image_verification: bool = False


class RepairPlan(BaseModel):
    problem: str
    category: str
    safety_level: SafetyLevel
    steps: List[RepairStep] = Field(min_length=1)
    estimated_minutes: Optional[int] = None
    stop_conditions: List[str] = Field(default_factory=list)
    sources: List[str] = Field(default_factory=list)
    disclaimer: str = DISCLAIMER


class ConversationRequest(BaseModel):
    """Full history each turn — the customer's answers must never be asked for twice."""

    messages: List[Message] = Field(min_length=1)
    # The service holds no state between turns, so the caller sends back where the repair had got
    # to. Absent, the repair restarts at NEW — which is right for a new conversation and merely
    # conservative for an existing one, since the transcript is replayed anyway.
    current_state: Optional[RepairState] = None
    correlation_id: Optional[str] = None


class ConversationResponse(BaseModel):
    status: ConversationStatus
    # The precise state, alongside the coarse status. `status` says what the client must do next
    # and stays stable; `state` says where the repair is, and is what a progress indicator or
    # status line should render. Both are returned so existing callers keep working.
    state: RepairState = RepairState.NEW
    category: Optional[str] = None
    problem: Optional[str] = None
    confidence: float = Field(ge=0.0, le=1.0)
    safety: SafetyAssessment
    # Exactly one question at a time — a list of questions reads as an interrogation.
    question: Optional[str] = None
    quick_replies: List[str] = Field(default_factory=list)
    requires_image: bool = False
    message: str
    repair_plan: Optional[RepairPlan] = None
    correlation_id: Optional[str] = None


class VerifyStepRequest(BaseModel):
    step_number: int = Field(ge=1)
    instruction: str
    expected_result: Optional[str] = None
    image_urls: List[str] = Field(min_length=1)
    correlation_id: Optional[str] = None


class VerifyStepResponse(BaseModel):
    step_number: int
    verification: VerificationResult
    confidence: float = Field(ge=0.0, le=1.0)
    reason: str
    next_action: str
    disclaimer: str = DISCLAIMER
    correlation_id: Optional[str] = None
