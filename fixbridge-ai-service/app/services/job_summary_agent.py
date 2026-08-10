"""Turns a repair conversation into a briefing for a contractor.

The point is that the customer has already explained the problem once, answered follow-up questions
and sent photos. Making them retype all of it into a job request is the exact friction FixBridge
exists to remove.

The summary is written for a tradesperson deciding whether to accept: what the job is, what has
already been established, and what remains unknown. Confidentiality still applies — nothing here
mentions retail price or margin, and the address is not included, matching what a contractor sees
before a job is authorised.
"""

from __future__ import annotations

from typing import List, Optional

import structlog

from app.schemas.repair import Message, SafetyAssessment, SafetyLevel

log = structlog.get_logger()

TRADE_BY_CATEGORY = {
    "plumbing": "licensed_plumber",
    "electrical": "licensed_electrician",
    "hvac": "hvac_technician",
    "appliance": "appliance_engineer",
    "roofing": "roofer",
    "carpentry": "carpenter",
    "general": "handyman",
}


class JobSummaryAgent:
    """Builds the contractor-facing briefing. Deterministic assembly rather than free generation —
    a summary that invents a detail the customer never said would send a tradesperson to the wrong
    job with the wrong parts."""

    def required_trade(self, category: Optional[str]) -> str:
        return TRADE_BY_CATEGORY.get((category or "general").lower(), "handyman")

    def build(self, messages: List[Message], category: Optional[str],
              problem: Optional[str], safety: SafetyAssessment,
              image_count: int = 0) -> dict:
        customer_turns = [m.text.strip() for m in messages
                          if m.role == "customer" and m.text and m.text.strip()]

        # The customer's own words, kept verbatim. Paraphrasing a symptom is how "it clicks twice
        # then stops" becomes "it doesn't start", which sends the wrong engineer.
        description = customer_turns[0] if customer_turns else (problem or "Not described")
        answers = customer_turns[1:] if len(customer_turns) > 1 else []

        established: List[str] = []
        if answers:
            established.append("Customer answered follow-up questions: " + "; ".join(answers[:6]))
        if image_count:
            established.append(f"{image_count} photo(s) supplied and analysed")
        if safety.hazards:
            established.append("Hazard indicators noted: " + ", ".join(safety.hazards))

        outstanding: List[str] = []
        if not image_count:
            outstanding.append("No photographs were provided")
        if safety.level == SafetyLevel.INSUFFICIENT_INFORMATION:
            outstanding.append("The problem could not be assessed remotely with confidence")
        if safety.confidence < 0.7:
            outstanding.append("Remote assessment confidence was low — expect to diagnose on site")

        urgency = "EMERGENCY" if safety.level == SafetyLevel.EMERGENCY else (
            "HIGH" if safety.level == SafetyLevel.PROFESSIONAL_REQUIRED else "NORMAL")

        summary = {
            "required_trade": self.required_trade(category),
            "category": (category or "general").upper(),
            "problem": problem or description[:140],
            "customer_description": description,
            "established": established,
            "outstanding": outstanding,
            "images": image_count,
            "safety_level": safety.level.value,
            "safety_reasons": safety.reasons,
            "urgency": urgency,
            "why_escalated": self._why(safety),
            # Explicit, because the omission is deliberate rather than an oversight.
            "excluded": "Retail price, margin and the full address are withheld until the job is "
                        "authorised.",
        }
        log.info("job_summary_built", category=category, trade=summary["required_trade"],
                 urgency=urgency, established=len(established), outstanding=len(outstanding))
        return summary

    @staticmethod
    def _why(safety: SafetyAssessment) -> str:
        if safety.level == SafetyLevel.EMERGENCY:
            return ("Assessed as an emergency: " + "; ".join(safety.reasons)
                    if safety.reasons else "Assessed as an emergency.")
        if safety.level == SafetyLevel.PROFESSIONAL_REQUIRED:
            return ("Beyond safe self-repair: " + "; ".join(safety.reasons)
                    if safety.reasons else "Beyond safe self-repair.")
        if safety.level == SafetyLevel.INSUFFICIENT_INFORMATION:
            return "Could not be assessed remotely with enough confidence to advise."
        return "The customer chose to involve a professional."
