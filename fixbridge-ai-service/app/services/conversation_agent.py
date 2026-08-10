"""Drives the repair conversation.

Two behaviours matter more than the model here. First, the safety gate runs on the *whole*
conversation, not just the latest message — a customer who mentioned gas three turns ago is still
talking about gas. Second, a question is never asked twice: the caller replays the full history and
this agent tracks which facts have already been supplied.
"""

from __future__ import annotations

import re
from typing import List, Optional

import structlog

from app.schemas.repair import (
    ConversationRequest,
    ConversationResponse,
    ConversationStatus,
    Message,
    SafetyLevel,
)
from app.services.repair_planner import RepairPlanner
from app.services.state_machine import RepairState, allows_repair_plan
from app.services.safety_agent import SafetyAgent

log = structlog.get_logger()

# Category detection. Cheap, deterministic and good enough to route — the expensive model is only
# needed once we're actually writing a repair plan.
CATEGORIES = [
    ("plumbing", r"\bleak|\bpipe|\bdrain|\bsink|\btoilet|\btap\b|\bfaucet|\bwater\b|\bshower"),
    ("electrical", r"\boutlet|\bsocket|\bswitch|\blight|\bpower\b|\bbreaker|\bwiring|\bspark"),
    ("hvac", r"\bheat(ing|er)?\b|\bboiler|\bfurnace|\bac\b|air con|\bhvac|\bcooling|\bthermostat"),
    ("appliance", r"\bwashing machine|\bdishwasher|\bfridge|\bfreezer|\boven|\bdryer|\bmachine\b"),
    ("roofing", r"\broof|\bgutter|\bchimney|\btile\b|\bshingle"),
    ("carpentry", r"\bdoor\b|\bwindow|\bhinge|\bcabinet|\bshelf|\bfloor(board)?"),
]

# The follow-ups worth asking, in order. Each has a matcher that decides whether the conversation
# already answers it — that's what stops the assistant repeating itself.
FOLLOW_UPS = [
    ("plumbing", "Is the water leaking constantly, or only when the tap is running?",
     ["Constantly", "Only when running", "Not sure"], r"constant|only when|when.*run|not sure|drip"),
    ("plumbing", "Roughly how much water is there — a slow drip, or a steady flow?",
     ["A slow drip", "A steady flow", "Not sure"], r"drip|flow|puddle|pool|litre|gallon|bucket"),
    ("electrical", "Does anything else on that circuit still work?",
     ["Yes", "No", "Not sure"], r"still work|other (outlet|socket|light)|circuit|nothing works"),
    ("hvac", "Is the unit running at all — any noise or air movement?",
     ["Running but not working", "Completely dead", "Not sure"], r"running|noise|dead|nothing happens|blow"),
    ("appliance", "What happens when you switch it on?",
     ["Nothing at all", "It makes a noise", "It starts then stops"], r"nothing|noise|click|start|stop|beep"),
    ("*", "How long has this been happening?",
     ["Just started", "A few days", "Longer than a week"], r"day|week|month|hour|just start|since"),
]


class ConversationAgent:
    def __init__(self) -> None:
        self.safety = SafetyAgent()
        self.planner = RepairPlanner()

    def respond(self, request: ConversationRequest) -> ConversationResponse:
        history = request.messages
        customer_text = " ".join(m.text for m in history if m.role == "customer")
        has_images = any(m.image_urls for m in history)

        category = self._category(customer_text)
        confidence = self._confidence(customer_text, category, has_images)

        # Safety runs on the entire conversation, so an early mention still counts.
        safety = self.safety.assess(customer_text, confidence)

        # Escalation short-circuits everything: no questions, no plan, no DIY.
        if safety.level in (SafetyLevel.EMERGENCY, SafetyLevel.PROFESSIONAL_REQUIRED):
            log.info("conversation_escalated", level=safety.level, category=category,
                     correlation_id=request.correlation_id)
            return ConversationResponse(
                status=(ConversationStatus.EMERGENCY if safety.level == SafetyLevel.EMERGENCY
                        else ConversationStatus.PROFESSIONAL_REQUIRED),
                category=category, problem=self._problem(customer_text, category),
                confidence=confidence, safety=safety,
                message=self.safety.customer_message(safety),
                quick_replies=["Find a professional"],
                correlation_id=request.correlation_id,
            )

        # Ask the next unanswered question, one at a time.
        question = self._next_question(category, customer_text)
        if question:
            text, replies = question
            return ConversationResponse(
                status=ConversationStatus.NEED_MORE_INFORMATION,
                category=category, problem=self._problem(customer_text, category),
                confidence=confidence, safety=safety, question=text, quick_replies=replies,
                message=text, correlation_id=request.correlation_id,
            )

        # Questions exhausted; a photo is the next most useful thing.
        if not has_images:
            return ConversationResponse(
                status=ConversationStatus.NEED_IMAGE,
                category=category, problem=self._problem(customer_text, category),
                confidence=confidence, safety=safety, requires_image=True,
                message="Could you send a photo of the area? It helps me see what's going on.",
                quick_replies=["Take a photo", "Skip for now"],
                correlation_id=request.correlation_id,
            )

        # Enough is known — but a plan is only offered when the gate actually permits DIY.
        if not self.safety.allows_diy(safety):
            return ConversationResponse(
                status=ConversationStatus.PROFESSIONAL_REQUIRED,
                category=category, problem=self._problem(customer_text, category),
                confidence=confidence, safety=safety,
                message=self.safety.customer_message(safety),
                quick_replies=["Find a professional"],
                correlation_id=request.correlation_id,
            )

        # The state machine is the second, structural guard: even with a SAFE_DIY verdict, a plan
        # is only built from a state that permits one. The safety agent and the machine must agree.
        state = RepairState.SAFE_DIY
        if not allows_repair_plan(state):
            raise RuntimeError(f"State {state} must not produce a repair plan")
        plan = self.planner.plan(self._problem(customer_text, category), category, safety)
        return ConversationResponse(
            status=ConversationStatus.REPAIR_PLAN_READY,
            category=category, problem=plan.problem, confidence=confidence, safety=safety,
            message=f"I can talk you through this — {len(plan.steps)} steps. Ready to start?",
            quick_replies=["Yes, guide me", "Contact a professional"],
            repair_plan=plan, correlation_id=request.correlation_id,
        )

    # ---- internals ------------------------------------------------------------------------

    @staticmethod
    def _category(text: str) -> str:
        blob = text.lower()
        for name, pattern in CATEGORIES:
            if re.search(pattern, blob):
                return name
        return "general"

    @staticmethod
    def _confidence(text: str, category: str, has_images: bool) -> float:
        """Grows as the customer tells us more. Deliberately conservative — confidence gates DIY."""
        score = 0.3
        if category != "general":
            score += 0.2
        if len(text.split()) > 12:
            score += 0.15
        if len(text.split()) > 30:
            score += 0.1
        if has_images:
            score += 0.2
        return min(round(score, 2), 0.95)

    @staticmethod
    def _problem(text: str, category: str) -> str:
        first = text.strip().split(".")[0]
        return (first[:140] if first else f"Reported {category} issue")

    @staticmethod
    def _next_question(category: str, answered_text: str) -> Optional[tuple[str, List[str]]]:
        blob = answered_text.lower()
        for cat, question, replies, answered_pattern in FOLLOW_UPS:
            if cat not in (category, "*"):
                continue
            if re.search(answered_pattern, blob):
                continue  # already answered — never ask twice
            return question, replies
        return None
