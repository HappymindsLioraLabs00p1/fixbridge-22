"""Builds a structured RepairPlan.

Plans come from a curated library rather than being invented per request. A model asked to write
repair steps will produce plausible prose that is occasionally wrong in ways a homeowner cannot
detect — and here "wrong" means water damage or a shock. Known procedures are therefore stored and
retrieved; the model's job is selection and phrasing, never invention.

This is the seam a RAG pipeline replaces: swap the lookup for a pgvector similarity search over
manufacturer manuals and approved procedures, and the rest of the flow is unchanged.
"""

from __future__ import annotations

from typing import Dict, List

import structlog

from app.schemas.repair import RepairPlan, RepairStep, SafetyAssessment, SafetyLevel

log = structlog.get_logger()

STOP_CONDITIONS = [
    "You see or smell anything burning.",
    "Water starts flowing faster than before.",
    "A part won't move without force.",
    "You're unsure at any point — stopping is always the right call.",
]

# Curated procedures. Each is a real, low-risk homeowner task.
LIBRARY: Dict[str, Dict] = {
    "plumbing": {
        "problem": "Possible leak at a pipe connection under the sink",
        "minutes": 25,
        "steps": [
            dict(number=1,
                 instruction="Turn off the water at the isolation valves under the sink — the small "
                             "taps on the pipes. Turn them clockwise until they stop.",
                 why="Working on a live supply is how a small leak becomes a flood.",
                 tools=[], warnings=["If the valves won't turn, stop and call a plumber."],
                 expected_result="Water no longer runs when you open the tap above.",
                 requires_image_verification=False),
            dict(number=2,
                 instruction="Put a bowl and a towel under the pipes, then dry everything with the "
                             "towel so you can see where water reappears.",
                 why="A dry surface shows you the true source rather than where water collected.",
                 tools=["Towel", "Bowl"], warnings=[],
                 expected_result="The area under the sink is dry.",
                 requires_image_verification=True),
            dict(number=3,
                 instruction="Look at the connection where the pipes join. Tighten the plastic nut "
                             "by hand, clockwise, until it's firm. Do not use a wrench.",
                 why="These fittings seal with a rubber washer; a wrench cracks them.",
                 tools=[], warnings=["Hand-tight only. If it's already tight, don't force it."],
                 expected_result="The nut is firm and no longer turns easily by hand.",
                 requires_image_verification=True),
            dict(number=4,
                 instruction="Turn the water back on slowly and run the tap for thirty seconds while "
                             "you watch the connection.",
                 why="Slowly, so a failed seal seeps rather than sprays.",
                 tools=[], warnings=["If water appears, turn the supply off again and stop."],
                 expected_result="No water appears at the connection.",
                 requires_image_verification=True),
        ],
    },
    "appliance": {
        "problem": "Appliance not starting",
        "minutes": 15,
        "steps": [
            dict(number=1,
                 instruction="Check the appliance is switched on at the wall and the plug is fully in.",
                 why="It's the most common cause and costs nothing to rule out.",
                 tools=[], warnings=[], expected_result="The plug is secure and the socket is on.",
                 requires_image_verification=False),
            dict(number=2,
                 instruction="Plug a lamp or phone charger into the same socket to check it has power.",
                 why="This separates a broken appliance from a dead socket.",
                 tools=["A lamp or charger"],
                 warnings=["If the socket is dead, stop — that's electrical work."],
                 expected_result="You know whether the socket works.",
                 requires_image_verification=False),
            dict(number=3,
                 instruction="Check the door or lid is fully closed. Most machines refuse to start "
                             "otherwise. Open it and close it firmly once.",
                 why="The door switch is a safety interlock and a very common fault.",
                 tools=[], warnings=[], expected_result="The door clicks shut.",
                 requires_image_verification=True),
        ],
    },
    "carpentry": {
        "problem": "Loose or misaligned door",
        "minutes": 20,
        "steps": [
            dict(number=1,
                 instruction="Open the door and look at the hinges. Check whether any screws stand "
                             "proud of the metal plate.",
                 why="A door usually drops because one screw has worked loose.",
                 tools=[], warnings=[], expected_result="You've identified any loose screws.",
                 requires_image_verification=True),
            dict(number=2,
                 instruction="Tighten each loose screw with a screwdriver until it sits flush. Stop "
                             "when it resists.",
                 why="Overtightening strips the hole and makes the problem permanent.",
                 tools=["Screwdriver"], warnings=["Don't force a screw that spins freely."],
                 expected_result="Screws sit flush and the hinge is firm.",
                 requires_image_verification=True),
            dict(number=3,
                 instruction="Open and close the door a few times and watch where it catches.",
                 why="Confirms the fix rather than assuming it.",
                 tools=[], warnings=[], expected_result="The door swings freely and latches.",
                 requires_image_verification=False),
        ],
    },
}

GENERIC = {
    "problem": "General maintenance issue",
    "minutes": 15,
    "steps": [
        dict(number=1,
             instruction="Take a clear photo of the problem area in good light, from about arm's length.",
             why="A clear picture is what lets me narrow this down.",
             tools=[], warnings=[], expected_result="You have a well-lit photo.",
             requires_image_verification=True),
        dict(number=2,
             instruction="Check whether anything is visibly loose, cracked, blocked or disconnected.",
             why="Most simple faults are visible once you know to look.",
             tools=[], warnings=["Don't dismantle anything."],
             expected_result="You've described what you can see.",
             requires_image_verification=False),
    ],
}


class RepairPlanner:
    def plan(self, problem: str, category: str, safety: SafetyAssessment) -> RepairPlan:
        # A plan is only ever produced for a SAFE_DIY verdict. Reaching here otherwise is a bug, so
        # fail loudly rather than quietly emitting instructions.
        if safety.level != SafetyLevel.SAFE_DIY:
            raise ValueError(f"Refusing to build a repair plan for safety level {safety.level}")

        entry = LIBRARY.get(category, GENERIC)
        steps: List[RepairStep] = [RepairStep(**s) for s in entry["steps"]]
        log.info("repair_plan_built", category=category, steps=len(steps))
        return RepairPlan(
            problem=problem or entry["problem"],
            category=category,
            safety_level=safety.level,
            steps=steps,
            estimated_minutes=entry["minutes"],
            stop_conditions=STOP_CONDITIONS,
            sources=["FixBridge approved procedures"],
        )
