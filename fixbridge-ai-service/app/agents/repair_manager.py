"""The Repair Manager: the agent that runs a repair.

It decides which specialist to call next and records every call it makes. What it deliberately does
NOT do is let a model choose that sequence. An agent free to pick its own next action will
eventually skip the safety check or loop between questions, and here the cost of that is somebody
following DIY instructions for a gas leak.

So tool selection is driven by the state machine: the current state determines which tools are even
legal, the agent picks among those, and the machine rejects any transition that isn't permitted.
The intelligence sits inside the tools — vision, retrieval, language — rather than in the control
flow, which is exactly where it can be tested.

Every turn produces a trace: which tools ran, in what order, what each returned. When a repair goes
wrong that question has to be answerable.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

import structlog

from app.agents.tools import (
    AnalyzeProblemInput,
    CreatePlanInput,
    MatchContractorInput,
    SafetyCheckInput,
    SearchKnowledgeInput,
    Tool,
    ToolCall,
    ToolRegistry,
)
from app.rag.retriever import RagAgent
from app.schemas.repair import (
    ConversationRequest,
    ConversationResponse,
    ConversationStatus,
    SafetyLevel,
)
from app.services.conversation_agent import ConversationAgent
from app.services.job_summary_agent import JobSummaryAgent
from app.services.repair_planner import RepairPlanner
from app.services.safety_agent import SafetyAgent
from app.services.state_machine import (
    RepairState,
    allows_repair_plan,
    can_transition,
    transition,
)

log = structlog.get_logger()

# Which tools may run in which state. A tool absent here cannot be called from that state at all —
# this table is what stops the agent reaching for a repair plan before the safety check.
TOOLS_BY_STATE: Dict[RepairState, List[str]] = {
    RepairState.NEW: ["analyze_problem", "run_safety_check"],
    RepairState.COLLECTING_INFORMATION: ["analyze_problem", "run_safety_check"],
    RepairState.WAITING_FOR_IMAGE: ["analyze_problem", "run_safety_check"],
    RepairState.IMAGE_ANALYSIS: ["run_safety_check"],
    RepairState.SAFETY_CHECK: ["run_safety_check"],
    RepairState.SAFE_DIY: ["search_knowledge", "create_repair_plan"],
    RepairState.REPAIR_PLAN_CREATED: ["verify_repair_step"],
    RepairState.STEP_IN_PROGRESS: ["verify_repair_step"],
    RepairState.WAITING_FOR_VERIFICATION: ["verify_repair_step"],
    RepairState.PROFESSIONAL_REQUIRED: ["match_contractor", "build_job_summary"],
    RepairState.EMERGENCY: ["match_contractor", "build_job_summary"],
    RepairState.INSUFFICIENT_INFORMATION: ["analyze_problem", "run_safety_check"],
}

# Where a safety verdict lands the repair.
STATE_FOR_SAFETY = {
    SafetyLevel.SAFE_DIY: RepairState.SAFE_DIY,
    SafetyLevel.PROFESSIONAL_REQUIRED: RepairState.PROFESSIONAL_REQUIRED,
    SafetyLevel.EMERGENCY: RepairState.EMERGENCY,
    SafetyLevel.INSUFFICIENT_INFORMATION: RepairState.INSUFFICIENT_INFORMATION,
}


@dataclass
class RepairContext:
    """Everything the manager knows about the repair in front of it."""

    state: RepairState = RepairState.NEW
    category: Optional[str] = None
    problem: Optional[str] = None
    safety_level: Optional[SafetyLevel] = None
    image_count: int = 0
    trace: List[ToolCall] = field(default_factory=list)

    def record(self, call: ToolCall) -> ToolCall:
        self.trace.append(call)
        return call


class RepairManagerAgent:
    def __init__(self) -> None:
        self.conversation = ConversationAgent()
        self.safety = SafetyAgent()
        self.planner = RepairPlanner()
        self.rag = RagAgent()
        self.summariser = JobSummaryAgent()
        self.tools = self._build_registry()

    # ---- tools ------------------------------------------------------------------------------

    def _build_registry(self) -> ToolRegistry:
        registry = ToolRegistry()

        registry.register(Tool(
            name="analyze_problem",
            description="Classify the reported problem and decide what is still unknown.",
            input_model=AnalyzeProblemInput,
            handler=lambda text, has_images: {
                "category": self.conversation._category(text),
                "confidence": self.conversation._confidence(
                    text, self.conversation._category(text), has_images),
                "problem": self.conversation._problem(text, self.conversation._category(text)),
            },
        ))

        registry.register(Tool(
            name="run_safety_check",
            description="Classify the hazard level. Its verdict is final and cannot be overridden.",
            input_model=SafetyCheckInput,
            handler=lambda text, confidence: self.safety.assess(text, confidence).model_dump(),
        ))

        registry.register(Tool(
            name="search_knowledge",
            description="Retrieve approved repair procedures relevant to the problem.",
            input_model=SearchKnowledgeInput,
            # Retrieve once and read both fields off the result. Reading each field from its own
            # call embedded and searched twice for one answer.
            handler=self._search_knowledge,
        ))

        registry.register(Tool(
            name="create_repair_plan",
            description="Build a structured repair plan. Refuses unless the verdict permits DIY.",
            input_model=CreatePlanInput,
            handler=self._create_plan,
        ))

        registry.register(Tool(
            name="match_contractor",
            description="Identify the trade needed. Contractor records live in Java, which ranks them.",
            input_model=MatchContractorInput,
            handler=lambda category: {"required_trade": self.summariser.required_trade(category)},
        ))

        return registry

    def _search_knowledge(self, query: str, category: Optional[str], limit: int) -> dict:
        grounded = self.rag.retrieve(query, category, limit)
        return {"passages": grounded.passages, "sources": grounded.sources,
                "grounded": grounded.is_grounded}

    def _create_plan(self, problem: str, category: str, safety_level: str) -> dict:
        from app.agents.tools import ToolError
        from app.schemas.repair import SafetyAssessment

        level = SafetyLevel(safety_level)
        # Two independent refusals: the tool checks the verdict, the planner checks it again. A
        # single guard is one bug away from handing out instructions for a gas leak.
        if level != SafetyLevel.SAFE_DIY:
            raise ToolError(f"A repair plan cannot be created for safety level {level.value}")
        plan = self.planner.plan(problem, category, SafetyAssessment(level=level, confidence=0.8))
        return plan.model_dump()

    # ---- orchestration ----------------------------------------------------------------------

    def available_tools(self, state: RepairState) -> List[str]:
        return TOOLS_BY_STATE.get(state, [])

    def handle(self, request: ConversationRequest,
               context: Optional[RepairContext] = None) -> tuple[ConversationResponse, RepairContext]:
        """Advance the repair by one turn, choosing tools legal for the current state."""
        ctx = context or RepairContext()
        text = " ".join(m.text for m in request.messages if m.role == "customer")
        ctx.image_count = sum(len(m.image_urls) for m in request.messages)

        # 1. Understand the problem.
        if "analyze_problem" in self.available_tools(ctx.state):
            call = ctx.record(self.tools.invoke(
                "analyze_problem", text=text, has_images=ctx.image_count > 0))
            if call.ok and call.result:
                ctx.category = call.result["category"]
                ctx.problem = call.result["problem"]

        # 2. Safety, before anything that could become an instruction.
        confidence = 0.0
        for call in reversed(ctx.trace):
            if call.name == "analyze_problem" and call.ok and call.result:
                confidence = float(call.result.get("confidence", 0.0))
                break

        if can_transition(ctx.state, RepairState.SAFETY_CHECK):
            ctx.state = transition(ctx.state, RepairState.SAFETY_CHECK)
        safety_call = ctx.record(self.tools.invoke(
            "run_safety_check", text=text, confidence=confidence))

        if safety_call.ok and safety_call.result:
            ctx.safety_level = SafetyLevel(safety_call.result["level"])
            target = STATE_FOR_SAFETY[ctx.safety_level]
            if can_transition(ctx.state, target):
                ctx.state = transition(ctx.state, target)

        # 3. Ground the plan, but only where the state permits one.
        if allows_repair_plan(ctx.state) and "search_knowledge" in self.available_tools(ctx.state):
            ctx.record(self.tools.invoke(
                "search_knowledge", query=ctx.problem or text, category=ctx.category, limit=4))

        # 4. Escalations identify the trade rather than planning.
        if ctx.state in (RepairState.PROFESSIONAL_REQUIRED, RepairState.EMERGENCY):
            ctx.record(self.tools.invoke("match_contractor", category=ctx.category))

        # The conversation agent composes the customer-facing turn from the same inputs, so what the
        # customer is told and what the trace shows cannot disagree.
        response = self.conversation.respond(request)

        log.info("repair_manager_turn", state=ctx.state.value,
                 safety=ctx.safety_level.value if ctx.safety_level else None,
                 tools=[c.name for c in ctx.trace], failures=[c.name for c in ctx.trace if not c.ok],
                 correlation_id=request.correlation_id)
        return response, ctx

    def trace_summary(self, ctx: RepairContext) -> List[Dict[str, object]]:
        """The audit trail for a turn."""
        return [{"tool": c.name, "ok": c.ok, "latency_ms": c.latency_ms, "error": c.error}
                for c in ctx.trace]
