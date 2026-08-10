"""Repair Manager tests.

The orchestrator's job is choosing what runs next, so these assert the *sequence* and the refusals —
that safety always runs before planning, that no tool can produce a plan from an escalated state,
and that a failing tool degrades the turn instead of ending it.
"""

import pytest

from app.agents.repair_manager import RepairContext, RepairManagerAgent
from app.agents.tools import Tool, ToolError, ToolRegistry
from app.schemas.repair import ConversationRequest, Message, SafetyLevel
from app.services.state_machine import RepairState

agent = RepairManagerAgent()


def request(*texts, images=0):
    msgs = [Message(role="customer", text=t) for t in texts]
    if images and msgs:
        msgs[-1].image_urls = [f"https://x/{i}.jpg" for i in range(images)]
    return ConversationRequest(messages=msgs)


# ---- tool registry ------------------------------------------------------------------------

def test_the_expected_tools_are_registered():
    for name in ("analyze_problem", "run_safety_check", "search_knowledge",
                 "create_repair_plan", "match_contractor"):
        assert name in agent.tools.names()


def test_registering_the_same_tool_twice_is_rejected():
    r = ToolRegistry()
    from app.agents.tools import SafetyCheckInput
    t = Tool(name="x", description="", input_model=SafetyCheckInput, handler=lambda **k: {})
    r.register(t)
    with pytest.raises(ValueError):
        r.register(t)


def test_an_unknown_tool_raises():
    with pytest.raises(ToolError):
        ToolRegistry().get("nope")


def test_bad_arguments_fail_the_call_not_the_process():
    call = agent.tools.invoke("run_safety_check")  # missing required `text`
    assert not call.ok and "Invalid arguments" in call.error


def test_a_failing_tool_is_recorded_rather_than_raised():
    r = ToolRegistry()
    from app.agents.tools import SafetyCheckInput

    def explode(**_):
        raise RuntimeError("boom")

    r.register(Tool(name="boom", description="", input_model=SafetyCheckInput, handler=explode))
    call = r.invoke("boom", text="x", confidence=0.5)
    assert not call.ok and "boom" in call.error.lower()


def test_every_call_records_its_latency():
    call = agent.tools.invoke("run_safety_check", text="a dripping tap", confidence=0.8)
    assert call.ok and call.latency_ms >= 0


# ---- state-gated tool availability ---------------------------------------------------------

def test_planning_tools_are_unavailable_before_the_safety_check():
    for state in (RepairState.NEW, RepairState.COLLECTING_INFORMATION, RepairState.SAFETY_CHECK):
        assert "create_repair_plan" not in agent.available_tools(state)


def test_planning_tools_become_available_once_the_verdict_is_safe():
    assert "create_repair_plan" in agent.available_tools(RepairState.SAFE_DIY)


def test_an_emergency_state_offers_only_escalation_tools():
    tools = agent.available_tools(RepairState.EMERGENCY)
    assert "create_repair_plan" not in tools
    assert "match_contractor" in tools


# ---- the plan tool refuses unsafe input ----------------------------------------------------

@pytest.mark.parametrize("level", ["EMERGENCY", "PROFESSIONAL_REQUIRED", "INSUFFICIENT_INFORMATION"])
def test_the_plan_tool_refuses_any_non_safe_verdict(level):
    call = agent.tools.invoke("create_repair_plan", problem="x", category="plumbing",
                              safety_level=level)
    assert not call.ok
    assert "cannot be created" in call.error


def test_the_plan_tool_works_for_a_safe_verdict():
    call = agent.tools.invoke("create_repair_plan", problem="leak under the sink",
                              category="plumbing", safety_level="SAFE_DIY")
    assert call.ok and call.result["steps"]


# ---- orchestration -------------------------------------------------------------------------

def test_safety_always_runs_before_any_plan():
    _, ctx = agent.handle(request("kitchen sink is leaking constantly, a slow drip, a few days",
                                  images=1))
    names = [c.name for c in ctx.trace]
    assert "run_safety_check" in names
    if "create_repair_plan" in names:
        assert names.index("run_safety_check") < names.index("create_repair_plan")


def test_a_gas_report_lands_in_the_emergency_state():
    _, ctx = agent.handle(request("I can smell gas near the boiler"))
    assert ctx.state == RepairState.EMERGENCY
    assert ctx.safety_level == SafetyLevel.EMERGENCY


def test_an_emergency_never_runs_the_planning_tool():
    _, ctx = agent.handle(request("I can smell gas and there is smoke"))
    assert "create_repair_plan" not in [c.name for c in ctx.trace]


def test_an_emergency_identifies_the_trade_instead():
    _, ctx = agent.handle(request("I can smell gas near the boiler"))
    assert "match_contractor" in [c.name for c in ctx.trace]


def test_a_safe_problem_retrieves_knowledge():
    _, ctx = agent.handle(request(
        "the cabinet door hinge is loose and rattling, started a few days ago, screws backing out",
        images=1))
    if ctx.safety_level == SafetyLevel.SAFE_DIY:
        assert "search_knowledge" in [c.name for c in ctx.trace]


def test_the_turn_produces_an_auditable_trace():
    _, ctx = agent.handle(request("my tap drips"))
    summary = agent.trace_summary(ctx)
    assert summary and all("tool" in row and "ok" in row for row in summary)


def test_the_customer_response_agrees_with_the_traced_verdict():
    """What the customer is told and what the trace shows must not diverge."""
    response, ctx = agent.handle(request("I can smell gas near the boiler"))
    assert response.safety.level == ctx.safety_level
    assert response.repair_plan is None
