"""Calls the vision model and returns a schema-valid assessment.

Two rules hold here regardless of provider: the model never sees a full-size photo (cost), and its
output is never trusted verbatim (correctness). Pricing is not computed here at all — FixBridge's
Java pricing engine owns that, and an AI-set price would be unauditable.
"""

from __future__ import annotations

import base64
import json
from typing import List, Optional

import structlog
from openai import OpenAI

from app.core.config import get_settings
from app.schemas.assessment import AssessmentResponse

log = structlog.get_logger()

SYSTEM_PROMPT = """You assess residential and commercial property maintenance problems.

Return ONLY a JSON object matching the schema. Never state or estimate a price — pricing is
calculated elsewhere.

Set professional_required = true and safe_diy_allowed = false for anything involving:
gas or combustion, major or high-voltage electrical, active flooding or sewage, fire, smoke or
carbon monoxide, structural damage, dangerous roof work, or suspected hazardous material.
Do the same whenever your confidence is below 0.5 — when unsure, route to a professional.

urgency: low | medium | high | emergency
complexity: simple | medium | complex
confidence: 0.0 to 1.0
recommended_trade: e.g. licensed_plumber, licensed_electrician, hvac_technician, handyman, roofer
safety_notes: immediate steps the occupant should take, or an empty list
"""

JSON_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "category", "urgency", "recommended_trade", "complexity", "confidence",
        "professional_required", "safe_diy_allowed", "assessment", "safety_notes",
    ],
    "properties": {
        "category": {"type": "string"},
        "urgency": {"type": "string", "enum": ["low", "medium", "high", "emergency"]},
        "recommended_trade": {"type": "string"},
        "complexity": {"type": "string", "enum": ["simple", "medium", "complex"]},
        "confidence": {"type": "number"},
        "professional_required": {"type": "boolean"},
        "safe_diy_allowed": {"type": "boolean"},
        "assessment": {"type": "string"},
        "safety_notes": {"type": "array", "items": {"type": "string"}},
        "estimated_labor_hours_min": {"type": ["number", "null"]},
        "estimated_labor_hours_max": {"type": ["number", "null"]},
    },
}

# Keywords that must never be routed to DIY, whatever the model replies. A model is a probabilistic
# component; the safety rule is not allowed to be probabilistic.
HAZARD_TERMS = (
    "gas", "carbon monoxide", "co leak", "smoke", "fire", "burning", "spark", "sparking",
    "electrocut", "shock", "sewage", "flood", "structural", "asbestos", "mould", "mold",
)


class VisionService:
    def __init__(self) -> None:
        self.settings = get_settings()
        self._client: Optional[OpenAI] = None

    @property
    def client(self) -> OpenAI:
        if self._client is None:
            self._client = OpenAI(
                api_key=self.settings.openai_api_key,
                base_url=self.settings.openai_base_url,
                timeout=self.settings.ai_timeout_seconds,
            )
        return self._client

    def assess(self, description: str, images: List[bytes],
               correlation_id: Optional[str] = None) -> AssessmentResponse:
        if self.settings.stub_mode or not self.settings.openai_api_key:
            return self._stub(description)

        content: List[dict] = [{"type": "text", "text": description}]
        for raw in images:
            # Processed images are sent inline as data URIs so the model never needs access to
            # FixBridge's storage, and no signed URL is handed to a third party.
            b64 = base64.b64encode(raw).decode()
            content.append({
                "type": "image_url",
                "image_url": {"url": f"data:image/jpeg;base64,{b64}", "detail": "low"},
            })

        response = self.client.chat.completions.create(
            model=self.settings.vision_model,
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": content},
            ],
            response_format={
                "type": "json_schema",
                "json_schema": {"name": "assessment", "strict": True, "schema": JSON_SCHEMA},
            },
        )
        raw_text = response.choices[0].message.content or ""
        data = self._parse(raw_text)
        data.update(provider="openai", model=self.settings.vision_model)
        assessment = AssessmentResponse(**data)
        return self._enforce_safety(assessment, description)

    def raw_json(self, prompt: str, images: List[bytes]) -> dict:
        """Ask the vision model a free-form question and get parsed JSON back.

        Used by the verification agent, which needs a different shape from an assessment."""
        content: List[dict] = [{"type": "text", "text": prompt}]
        for raw in images:
            b64 = base64.b64encode(raw).decode()
            content.append({"type": "image_url",
                            "image_url": {"url": f"data:image/jpeg;base64,{b64}", "detail": "low"}})
        response = self.client.chat.completions.create(
            model=self.settings.vision_model,
            messages=[{"role": "user", "content": content}],
            response_format={"type": "json_object"},
        )
        return self._parse(response.choices[0].message.content or "")

    @staticmethod
    def _parse(raw: str) -> dict:
        """Reasoning models narrate around their JSON and often fence it, so find the object rather
        than assuming the whole reply is one."""
        raw = raw.strip()
        if raw.startswith("```"):
            first = raw.find("\n")
            last = raw.rfind("```")
            if first > 0 and last > first:
                raw = raw[first + 1:last].strip()
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            start, depth, in_str, esc = raw.find("{"), 0, False, False
            for i in range(start if start >= 0 else len(raw), len(raw)):
                c = raw[i]
                if esc:
                    esc = False
                    continue
                if c == "\\":
                    esc = True
                    continue
                if c == '"':
                    in_str = not in_str
                    continue
                if in_str:
                    continue
                if c == "{":
                    depth += 1
                elif c == "}":
                    depth -= 1
                    if depth == 0:
                        return json.loads(raw[start:i + 1])
        raise ValueError("The assessment service returned no parsable JSON")

    @staticmethod
    def _enforce_safety(assessment: AssessmentResponse, description: str) -> AssessmentResponse:
        """Server-side safety floor. If the description mentions a hazard, DIY is refused however
        confident the model was — and low confidence is treated the same way."""
        text = description.lower()
        hazardous = any(term in text for term in HAZARD_TERMS)
        if hazardous or assessment.confidence < 0.5:
            assessment.safe_diy_allowed = False
            assessment.professional_required = True
            if hazardous and assessment.urgency in ("low", "medium"):
                assessment.urgency = "high"
        return assessment

    @staticmethod
    def _stub(description: str) -> AssessmentResponse:
        """Deterministic assessment so the whole pipeline runs with no API key and no spend."""
        text = description.lower()
        if any(t in text for t in ("gas", "smoke", "fire", "carbon monoxide")):
            category, trade, urgency = "gas", "licensed_gas_engineer", "emergency"
        elif any(t in text for t in ("spark", "outlet", "electric", "wiring")):
            category, trade, urgency = "electrical", "licensed_electrician", "emergency"
        elif any(t in text for t in ("leak", "pipe", "drain", "sink", "toilet", "water")):
            category, trade, urgency = "plumbing", "licensed_plumber", "high"
        elif any(t in text for t in ("heat", "boiler", "furnace", "ac", "hvac")):
            category, trade, urgency = "hvac", "hvac_technician", "high"
        else:
            category, trade, urgency = "handyman", "handyman", "medium"

        hazardous = urgency == "emergency"
        return AssessmentResponse(
            category=category,
            urgency=urgency,
            recommended_trade=trade,
            complexity="medium",
            confidence=0.72,
            professional_required=True,
            safe_diy_allowed=not hazardous and category == "handyman",
            assessment=f"Reported issue assessed as {category}. A {trade.replace('_', ' ')} is recommended.",
            safety_notes=["Stop using the affected fixture until it has been inspected."] if hazardous else [],
            estimated_labor_hours_min=1.0,
            estimated_labor_hours_max=3.0,
            provider="stub",
            model="stub-vision-v1",
        )
