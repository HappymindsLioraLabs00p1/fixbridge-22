"""Checks a customer's progress photo against what a step was supposed to achieve.

The bias here is deliberate: uncertainty escalates. Telling someone a joint is sealed when the photo
doesn't actually show that is how a "verified" repair becomes a flooded kitchen, so anything short
of a confident yes stops the workflow rather than continuing it.
"""

from __future__ import annotations

from typing import List, Optional

import structlog

from app.core.config import get_settings
from app.schemas.repair import VerificationResult, VerifyStepResponse
from app.services.vision_service import VisionService

log = structlog.get_logger()

# Below this we do not claim the step is done, whatever the model reported.
CONFIDENT = 0.75


class VerificationAgent:
    def __init__(self) -> None:
        self.settings = get_settings()
        self.vision = VisionService()

    def verify(self, step_number: int, instruction: str, images: List[bytes],
               expected_result: Optional[str] = None,
               correlation_id: Optional[str] = None) -> VerifyStepResponse:
        if not images:
            return self._response(step_number, VerificationResult.UNCERTAIN, 0.0,
                                  "I didn't receive a usable photo.",
                                  "Please send a photo of the area in good light.", correlation_id)

        result, confidence, reason = self._inspect(instruction, expected_result, images)

        # Confidence gate applied after the fact, so a hedged "yes" never counts as done.
        if result == VerificationResult.STEP_COMPLETED and confidence < CONFIDENT:
            log.info("verification_downgraded", step=step_number, confidence=confidence,
                     correlation_id=correlation_id)
            return self._response(
                step_number, VerificationResult.UNCERTAIN, confidence,
                "I can't confidently tell from this photo whether that's done.",
                "Send another photo a little closer, or ask a professional to check.", correlation_id)

        next_action = {
            VerificationResult.STEP_COMPLETED: "Continue to the next step.",
            VerificationResult.STEP_NOT_COMPLETED: "Have another go at this step, then send a new photo.",
            VerificationResult.UNCERTAIN: "Send another photo, closer and in better light.",
            VerificationResult.ESCALATE: "Stop here — this needs a professional.",
        }[result]

        log.info("step_verified", step=step_number, result=result, confidence=confidence,
                 correlation_id=correlation_id)
        return self._response(step_number, result, confidence, reason, next_action, correlation_id)

    def _inspect(self, instruction: str, expected: Optional[str],
                 images: List[bytes]) -> tuple[VerificationResult, float, str]:
        """Ask the vision model, then treat its answer as advisory rather than authoritative."""
        if self.settings.stub_mode or not self.settings.openai_api_key:
            # Development stub. Deliberately returns UNCERTAIN rather than a cheerful pass, so a
            # stubbed environment can never look like it verified real work.
            return (VerificationResult.UNCERTAIN, 0.5,
                    "Running without a vision model, so I can't confirm this from the photo.")

        prompt = (
            "A homeowner was asked to do this repair step:\n"
            f"{instruction}\n"
            + (f"Expected result: {expected}\n" if expected else "")
            + "Looking only at the photo, does it show the step has been completed?\n"
              "Answer honestly — say so if the photo doesn't show enough. Reply as JSON with "
              "verification (STEP_COMPLETED | STEP_NOT_COMPLETED | UNCERTAIN | ESCALATE), "
              "confidence (0-1) and a one-sentence reason."
        )
        try:
            raw = self.vision.raw_json(prompt, images)
            value = str(raw.get("verification", "UNCERTAIN")).upper()
            result = (VerificationResult(value) if value in VerificationResult.__members__
                      else VerificationResult.UNCERTAIN)
            confidence = float(raw.get("confidence", 0.0))
            reason = str(raw.get("reason", "")) or "Assessed from the photo."
            return result, max(0.0, min(confidence, 1.0)), reason
        except Exception as exc:  # provider failure must not read as success
            log.warning("verification_failed", error=str(exc))
            return (VerificationResult.UNCERTAIN, 0.0,
                    "I had trouble analysing that photo.")

    @staticmethod
    def _response(step: int, result: VerificationResult, confidence: float, reason: str,
                  next_action: str, correlation_id: Optional[str]) -> VerifyStepResponse:
        return VerifyStepResponse(step_number=step, verification=result,
                                  confidence=round(confidence, 2), reason=reason,
                                  next_action=next_action, correlation_id=correlation_id)
