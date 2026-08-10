"""The safety gate.

Deliberately not an LLM decision. A model asked "is this safe?" will sometimes say yes about a gas
leak, and the cost of that error is someone's house. Hazard classification is therefore rule-based
and deterministic; the model is only allowed to make a *safe* verdict less permissive, never more.

The rules encode the categories FixBridge must never hand to a DIY workflow: gas and combustion,
mains electrical, structural, fire, flooding and sewage, roof work, and hazardous material.
"""

from __future__ import annotations

import re
from typing import Iterable, List, Tuple

from app.schemas.repair import SafetyAssessment, SafetyLevel

# Anything matching these is escalated regardless of what any model concludes.
# Grouped so the reason given to the customer names the actual hazard.
HAZARD_RULES: List[Tuple[str, str, SafetyLevel]] = [
    # --- immediate danger to life -----------------------------------------------------------
    (r"\bgas\b|\bpropane\b|\bmethane\b|carbon monoxide|\bco\s?(leak|alarm)\b",
     "a possible gas or carbon-monoxide hazard", SafetyLevel.EMERGENCY),
    (r"\bfire\b|\bflames?\b|\bburning\b|\bsmoke\b|\bsmoul?der",
     "signs of fire or smoke", SafetyLevel.EMERGENCY),
    (r"\bspark(s|ing|ed)?\b|\belectrocut|\bshocked?\b|live wire|exposed wir",
     "an active electrical hazard", SafetyLevel.EMERGENCY),
    (r"\bflood(ing|ed)?\b|burst pipe|water pouring|sewage|\bsewer\b",
     "flooding or sewage", SafetyLevel.EMERGENCY),

    # --- work that legally or practically needs a professional -------------------------------
    (r"\bwiring\b|\bcircuit breaker\b|\bfuse ?box\b|consumer unit|\bmains\b|\d{3}\s?volt|high[- ]voltage",
     "mains electrical work", SafetyLevel.PROFESSIONAL_REQUIRED),
    (r"\bboiler\b|\bfurnace\b|water heater|\bflue\b",
     "a combustion appliance", SafetyLevel.PROFESSIONAL_REQUIRED),
    (r"\bstructural\b|load[- ]bearing|\bfoundation\b|\bsubsidence\b|\bbeam\b|\bjoist\b|major crack",
     "possible structural damage", SafetyLevel.PROFESSIONAL_REQUIRED),
    (r"\broof\b|\bchimney\b|\bgutter\b|\bladder\b|working at height",
     "work at height", SafetyLevel.PROFESSIONAL_REQUIRED),
    (r"\basbestos\b|\bmould\b|\bmold\b|\blead paint\b",
     "possible hazardous material", SafetyLevel.PROFESSIONAL_REQUIRED),
    (r"\bbridge\b|retaining wall|\bbalcony\b",
     "structural infrastructure", SafetyLevel.PROFESSIONAL_REQUIRED),
]

# Below this, we don't claim to understand the problem well enough to advise on it.
MIN_CONFIDENCE_FOR_DIY = 0.6


class SafetyAgent:
    """Classifies a problem into one of four levels. Rules win; a model can only escalate."""

    def assess(self, text: str, confidence: float = 0.0,
               model_opinion: SafetyLevel | None = None) -> SafetyAssessment:
        blob = (text or "").lower()
        hazards: List[str] = []
        level = SafetyLevel.SAFE_DIY

        for pattern, description, rule_level in HAZARD_RULES:
            if re.search(pattern, blob):
                hazards.append(description)
                level = self._stricter(level, rule_level)

        reasons: List[str] = []
        if hazards:
            reasons.append("Mentions " + ", ".join(hazards) + ".")

        # Not knowing enough is its own outcome, distinct from "safe".
        if not hazards and confidence < MIN_CONFIDENCE_FOR_DIY:
            level = SafetyLevel.INSUFFICIENT_INFORMATION
            reasons.append("There isn't enough information yet to judge this safely.")

        # A model may make the verdict stricter, never looser.
        if model_opinion is not None:
            stricter = self._stricter(level, model_opinion)
            if stricter != level:
                reasons.append("The assessment flagged additional risk.")
                level = stricter

        if level == SafetyLevel.SAFE_DIY and not reasons:
            reasons.append("No hazard indicators were found in what you've described.")

        return SafetyAssessment(
            level=level,
            reasons=reasons,
            hazards=hazards,
            confidence=round(confidence, 2),
        )

    @staticmethod
    def _stricter(a: SafetyLevel, b: SafetyLevel) -> SafetyLevel:
        """Higher rank wins, so combining verdicts can only ever tighten the outcome."""
        rank = {
            SafetyLevel.SAFE_DIY: 0,
            SafetyLevel.INSUFFICIENT_INFORMATION: 1,
            SafetyLevel.PROFESSIONAL_REQUIRED: 2,
            SafetyLevel.EMERGENCY: 3,
        }
        return a if rank[a] >= rank[b] else b

    @staticmethod
    def allows_diy(assessment: SafetyAssessment) -> bool:
        return assessment.level == SafetyLevel.SAFE_DIY

    @staticmethod
    def customer_message(assessment: SafetyAssessment) -> str:
        """What the customer is told. Plain language, no jargon, no false reassurance."""
        if assessment.level == SafetyLevel.EMERGENCY:
            return ("Please stop and make yourself safe. Based on what you've described this needs "
                    "immediate professional attention — if there's any risk to you, leave the "
                    "property and call the emergency services.")
        if assessment.level == SafetyLevel.PROFESSIONAL_REQUIRED:
            return ("This isn't something I can safely talk you through. A qualified professional "
                    "should look at it.")
        if assessment.level == SafetyLevel.INSUFFICIENT_INFORMATION:
            return "I need a little more detail before I can suggest anything."
        return "This looks like something you can safely try yourself."
