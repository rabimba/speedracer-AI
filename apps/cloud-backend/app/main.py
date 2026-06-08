from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timedelta, timezone
from typing import Literal, Optional

from fastapi import FastAPI
from pydantic import BaseModel, Field

app = FastAPI(title="Trustable AI Cloud Backend", version="0.1.0")

CoachAction = Literal[
    "BRAKE",
    "WAIT",
    "TURN_IN",
    "THROTTLE",
    "PUSH",
    "HUSTLE",
    "SPIKE_BRAKE",
    "EARLY_THROTTLE",
    "COGNITIVE_OVERLOAD",
]


class TelemetryFrame(BaseModel):
    time: float
    distance: Optional[float] = None
    speed: float
    brake: float
    throttle: float
    gLat: float = 0.0
    gLong: float = 0.0


class SessionAnalyzeRequest(BaseModel):
    sessionId: str
    trackName: str = "Sonoma Raceway"
    frames: list[TelemetryFrame]


class CornerDelta(BaseModel):
    cornerId: int
    cornerName: str
    phase: str
    speedDeltaMph: float
    brakeDeltaPercent: float
    throttleDeltaPercent: float
    recommendedAction: CoachAction
    cue: str


class SessionAnalyzeResponse(BaseModel):
    sessionId: str
    trackName: str
    inferenceRoute: Literal["vertex", "local_gemma"]
    deltas: list[CornerDelta]
    summary: str


class LearningPlanTarget(BaseModel):
    cornerId: int
    cornerName: str
    phases: list[str]
    targetDelta: str
    allowedCueActions: list[CoachAction]


class LearningPlan(BaseModel):
    schemaVersion: int = 1
    id: str
    driverId: str
    trackName: str
    focus: str
    objective: str
    generatedAt: str
    expiresAt: str
    targets: list[LearningPlanTarget]
    ignoredActions: list[CoachAction] = Field(default_factory=list)
    maxCueWords: int = 7
    notes: Optional[str] = None


class LearningPlanEnvelope(BaseModel):
    plan: LearningPlan
    digest: str
    signedAt: str
    signer: str


class LearningPlanRequest(BaseModel):
    driverId: str = "default-driver"
    trackName: str = "Sonoma Raceway"
    focus: str = "late_apex"
    deltas: list[CornerDelta] = Field(default_factory=list)


class RagQueryRequest(BaseModel):
    query: str
    topK: int = 3


SONOMA_REFERENCES = [
    {"cornerId": 2, "cornerName": "Turn 2", "phase": "APEX", "distance": 390, "speed": 70, "brake": 8, "throttle": 18},
    {"cornerId": 3, "cornerName": "Turn 3", "phase": "APEX", "distance": 640, "speed": 52, "brake": 4, "throttle": 16},
    {"cornerId": 7, "cornerName": "Turn 7", "phase": "APEX", "distance": 2190, "speed": 52, "brake": 3, "throttle": 18},
    {"cornerId": 11, "cornerName": "Turn 11", "phase": "APEX", "distance": 3700, "speed": 42, "brake": 5, "throttle": 10},
    {"cornerId": 12, "cornerName": "Turn 12", "phase": "APEX", "distance": 3990, "speed": 80, "brake": 0, "throttle": 64},
]

DOCTRINE = [
    {
        "id": "late-apex",
        "title": "Late Apex Discipline",
        "text": "Delay turn-in when exit speed matters; a later apex opens the wheel before throttle.",
        "tags": ["late apex", "turn in", "exit"],
    },
    {
        "id": "brake-release",
        "title": "Brake Release Shape",
        "text": "Brake pressure should taper as steering rises so the front tire load transfers cleanly.",
        "tags": ["brake", "trail", "release"],
    },
    {
        "id": "vision",
        "title": "Eyes Lead Hands",
        "text": "Vision should be through the exit before the car reaches the apex.",
        "tags": ["vision", "eyes", "hands"],
    },
]


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "inferenceRoute": inference_route()}


@app.post("/sessions/analyze", response_model=SessionAnalyzeResponse)
def analyze_session(request: SessionAnalyzeRequest) -> SessionAnalyzeResponse:
    deltas = build_deltas(request.frames)
    worst = sorted(deltas, key=lambda delta: delta.speedDeltaMph)[0] if deltas else None
    summary = (
        f"Highest-value focus: {worst.cornerName} {worst.phase}, cue '{worst.cue}'."
        if worst
        else "No reliable corner-local deltas found."
    )
    return SessionAnalyzeResponse(
        sessionId=request.sessionId,
        trackName=request.trackName,
        inferenceRoute=inference_route(),
        deltas=deltas,
        summary=summary,
    )


@app.post("/learning-plans", response_model=LearningPlanEnvelope)
def create_learning_plan(request: LearningPlanRequest) -> LearningPlanEnvelope:
    now = datetime.now(timezone.utc)
    selected = select_plan_targets(request.deltas)
    plan = LearningPlan(
        id=f"lp-{request.driverId}-{int(now.timestamp())}",
        driverId=request.driverId,
        trackName=request.trackName,
        focus=request.focus,
        objective=objective_for_focus(request.focus, selected),
        generatedAt=now.isoformat(),
        expiresAt=(now + timedelta(days=30)).isoformat(),
        targets=selected,
        ignoredActions=["HUSTLE", "PUSH"] if request.focus == "late_apex" else [],
        notes="Generated from DEL deltas. Cloud route may be Vertex or local Gemma fallback.",
    )
    payload = stable_json(plan.model_dump())
    return LearningPlanEnvelope(
        plan=plan,
        digest=hashlib.sha256(payload.encode("utf-8")).hexdigest(),
        signedAt=now.isoformat(),
        signer=inference_route(),
    )


@app.post("/rag/query")
def rag_query(request: RagQueryRequest) -> dict[str, object]:
    terms = {term.lower() for term in request.query.split() if term.strip()}
    scored = sorted(
        DOCTRINE,
        key=lambda item: len(terms.intersection(set(item["tags"]))),
        reverse=True,
    )
    return {"results": scored[: max(1, request.topK)]}


def build_deltas(frames: list[TelemetryFrame]) -> list[CornerDelta]:
    if not frames:
        return []
    deltas: list[CornerDelta] = []
    for reference in SONOMA_REFERENCES:
        frame = nearest_frame(frames, float(reference["distance"]))
        if frame is None:
            continue
        speed_delta = frame.speed - float(reference["speed"])
        brake_delta = frame.brake - float(reference["brake"])
        throttle_delta = frame.throttle - float(reference["throttle"])
        action, cue = action_and_cue(speed_delta, brake_delta, throttle_delta)
        deltas.append(
            CornerDelta(
                cornerId=int(reference["cornerId"]),
                cornerName=str(reference["cornerName"]),
                phase=str(reference["phase"]),
                speedDeltaMph=round(speed_delta, 2),
                brakeDeltaPercent=round(brake_delta, 2),
                throttleDeltaPercent=round(throttle_delta, 2),
                recommendedAction=action,
                cue=cue,
            )
        )
    return deltas


def nearest_frame(frames: list[TelemetryFrame], distance: float) -> Optional[TelemetryFrame]:
    distance_frames = [frame for frame in frames if frame.distance is not None]
    if not distance_frames:
        return None
    return min(distance_frames, key=lambda frame: abs(float(frame.distance or 0) - distance))


def action_and_cue(speed_delta: float, brake_delta: float, throttle_delta: float) -> tuple[CoachAction, str]:
    if throttle_delta > 18:
        return "EARLY_THROTTLE", "Wait. Squeeze throttle later."
    if speed_delta > 10 and brake_delta < -12:
        return "BRAKE", "Brake now. Keep it straight."
    if speed_delta < -12:
        return "HUSTLE", "Carry more speed next time."
    if brake_delta > 22:
        return "SPIKE_BRAKE", "Squeeze brake. No stab."
    return "WAIT", "Wait for it. Turn now."


def select_plan_targets(deltas: list[CornerDelta]) -> list[LearningPlanTarget]:
    selected = sorted(deltas, key=lambda delta: delta.speedDeltaMph)[:3]
    if not selected:
        selected = [
            CornerDelta(
                cornerId=11,
                cornerName="Turn 11",
                phase="BRAKE_ZONE",
                speedDeltaMph=0,
                brakeDeltaPercent=0,
                throttleDeltaPercent=0,
                recommendedAction="WAIT",
                cue="Wait for it. Turn now.",
            )
        ]
    return [
        LearningPlanTarget(
            cornerId=delta.cornerId,
            cornerName=delta.cornerName,
            phases=["BRAKE_ZONE", "TURN_IN"],
            targetDelta="delay turn-in until the late apex reference",
            allowedCueActions=["WAIT", "TURN_IN"],
        )
        for delta in selected
    ]


def objective_for_focus(focus: str, targets: list[LearningPlanTarget]) -> str:
    corners = ", ".join(target.cornerName for target in targets)
    if focus == "late_apex":
        return f"Coach only late apex timing at {corners}."
    return f"Coach only {focus.replace('_', ' ')} at {corners}."


def inference_route() -> Literal["vertex", "local_gemma"]:
    return "vertex" if os.getenv("VERTEX_AI_AVAILABLE") == "true" else "local_gemma"


def stable_json(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"))
