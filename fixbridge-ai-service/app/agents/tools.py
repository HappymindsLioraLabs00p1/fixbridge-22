"""The tools the Repair Manager can invoke.

Every tool declares its inputs and outputs, times out, and records what it did. That bookkeeping is
the point: when a repair goes wrong, "which tools ran, in what order, and what did each return" has
to be answerable, and a bare function call leaves no trace.

A tool never decides policy. `run_safety_check` returns the safety agent's verdict; it cannot
overrule it, and no tool can produce a repair plan for a state that forbids one.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

import structlog
from pydantic import BaseModel, ValidationError

log = structlog.get_logger()

DEFAULT_TIMEOUT_SECONDS = 30.0


class ToolError(Exception):
    """A tool failed. Carried rather than raised through the agent, so one failing tool degrades the
    turn instead of ending the conversation."""


@dataclass
class ToolCall:
    """A record of one invocation, for the trace."""

    name: str
    arguments: Dict[str, Any]
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None
    latency_ms: int = 0

    @property
    def ok(self) -> bool:
        return self.error is None


@dataclass
class Tool:
    name: str
    description: str
    input_model: type[BaseModel]
    handler: Callable[..., Any]
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS

    def invoke(self, arguments: Dict[str, Any]) -> ToolCall:
        call = ToolCall(name=self.name, arguments=arguments)
        started = time.perf_counter()
        try:
            # Validating before the handler runs means a malformed call fails cheaply and with a
            # message that names the field, rather than deep inside an agent.
            validated = self.input_model(**arguments)
            output = self.handler(**validated.model_dump())
            call.result = output if isinstance(output, dict) else {"value": output}
        except ValidationError as exc:
            call.error = f"Invalid arguments for {self.name}: {exc.errors()[0].get('msg', 'invalid')}"
        except ToolError as exc:
            call.error = str(exc)
        except Exception as exc:  # never let a tool crash the turn
            log.exception("tool_failed", tool=self.name)
            call.error = f"{self.name} failed: {exc.__class__.__name__}"
        finally:
            call.latency_ms = round((time.perf_counter() - started) * 1000)
            log.info("tool_call", tool=self.name, ok=call.ok,
                     latency_ms=call.latency_ms, error=call.error)
        return call


class ToolRegistry:
    """The set of tools available to an agent. Explicit registration rather than reflection, so
    adding a tool is a deliberate act and the available surface is readable in one place."""

    def __init__(self) -> None:
        self._tools: Dict[str, Tool] = {}

    def register(self, tool: Tool) -> None:
        if tool.name in self._tools:
            raise ValueError(f"Tool {tool.name} is already registered")
        self._tools[tool.name] = tool

    def get(self, name: str) -> Tool:
        tool = self._tools.get(name)
        if tool is None:
            raise ToolError(f"No such tool: {name}")
        return tool

    def invoke(self, name: str, **arguments: Any) -> ToolCall:
        return self.get(name).invoke(arguments)

    def names(self) -> List[str]:
        return sorted(self._tools)

    def describe(self) -> List[Dict[str, str]]:
        """For logging and for handing a model the available tools."""
        return [{"name": t.name, "description": t.description} for t in self._tools.values()]


# ---- Tool input schemas ---------------------------------------------------------------------

class AnalyzeProblemInput(BaseModel):
    text: str
    has_images: bool = False


class SafetyCheckInput(BaseModel):
    text: str
    confidence: float = 0.0


class SearchKnowledgeInput(BaseModel):
    query: str
    category: Optional[str] = None
    limit: int = 4


class CreatePlanInput(BaseModel):
    problem: str
    category: str
    safety_level: str


class VerifyStepInput(BaseModel):
    step_number: int
    instruction: str
    expected_result: Optional[str] = None


class MatchContractorInput(BaseModel):
    category: Optional[str] = None
