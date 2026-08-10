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
from app.services.job_summary_agent import JobSummaryAgent
from app.services.verification_agent import VerificationAgent

router = APIRouter(prefix="/v1/repair", tags=["repair"],
                   dependencies=[Depends(require_service_token), Depends(rate_limit)])

conversation = ConversationAgent()
verifier = VerificationAgent()
summariser = JobSummaryAgent()
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


@router.post("/job-summary")
async def job_summary(payload: ConversationRequest, request: Request) -> dict:
    """Turn a conversation into a contractor briefing.

    Called when a repair escalates, so the customer doesn't retype what they've already explained.
    Java persists the result against the job; this service keeps none of it.
    """
    cid = payload.correlation_id or request.headers.get("X-Correlation-Id") or new_correlation_id()
    correlation_id.set(cid)

    # Reuse the conversation agent so the summary reflects the same category and safety verdict the
    # customer was shown — a briefing that disagrees with what they were told is worse than none.
    view = conversation.respond(payload)
    # Named distinctly: `images` at module scope is the image service, and shadowing it here would
    # be a trap for the next person to add a line to this function.
    image_count = sum(len(m.image_urls) for m in payload.messages)
    summary = summariser.build(payload.messages, view.category, view.problem, view.safety, image_count)
    summary["correlation_id"] = cid
    return summary
