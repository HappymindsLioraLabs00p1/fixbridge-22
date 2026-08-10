"""Conversation and repair-workflow endpoints.

Stateless by design: the caller (the Java backend) owns conversation history and repair state and
replays what's needed. This service never writes to FixBridge's database.
"""

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.core.logging import correlation_id, new_correlation_id
from app.core.security import rate_limit, require_service_token
from app.schemas.repair import (
    ConversationRequest,
    ConversationResponse,
    VerifyStepRequest,
    VerifyStepResponse,
)
from app.services.ai_assessment_service import AiAssessmentService
from app.services.conversation_agent import ConversationAgent
from app.services.verification_agent import VerificationAgent

router = APIRouter(prefix="/v1/repair", tags=["repair"],
                   dependencies=[Depends(require_service_token), Depends(rate_limit)])

conversation = ConversationAgent()
verifier = VerificationAgent()
images = AiAssessmentService()


@router.post("/converse", response_model=ConversationResponse)
async def converse(payload: ConversationRequest, request: Request) -> ConversationResponse:
    """Advance the repair conversation by one turn.

    Returns a structured decision — a question, a request for a photo, an escalation, or a repair
    plan — so the client never has to interpret free text.
    """
    cid = payload.correlation_id or request.headers.get("X-Correlation-Id") or new_correlation_id()
    correlation_id.set(cid)
    payload.correlation_id = cid
    return conversation.respond(payload)


@router.post("/verify-step", response_model=VerifyStepResponse)
async def verify_step(payload: VerifyStepRequest, request: Request) -> VerifyStepResponse:
    """Check a progress photo against what the step was meant to achieve."""
    cid = payload.correlation_id or request.headers.get("X-Correlation-Id") or new_correlation_id()
    correlation_id.set(cid)

    processed, infos, failures = await images.process_urls([str(u) for u in payload.image_urls], cid)
    if not processed:
        reason = failures[0]["reason"] if failures else "No usable image was provided"
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, reason)

    return verifier.verify(payload.step_number, payload.instruction, processed,
                           payload.expected_result, cid)
