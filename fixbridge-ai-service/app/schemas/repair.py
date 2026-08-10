"""Conversation, safety and repair-plan contracts.

Everything the frontend consumes is a validated object, never raw model text — a chat UI that
parses prose is a UI that breaks whenever the model rephrases itself.
"""

from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field


class SafetyLevel(str, Enum):
    """The four outcomes the safety gate is allowed to produce."""

    SAFE_DIY = "SAFE_DIY"
    PROFESSIONAL_REQUIRED = "PROFESSIONAL_REQUIRED"
    EMERGENCY = "EMERGENCY"
    INSUFFICIENT_INFORMATION = "INSUFFICIENT_INFORMATION"


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
    correlation_id: Optional[str] = None


class ConversationResponse(BaseModel):
    status: ConversationStatus
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
