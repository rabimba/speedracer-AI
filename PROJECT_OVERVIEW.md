# Racecraft Project Overview

Racecraft is a real-time AI driving coach for track-day telemetry. The system is built around a split-brain runtime: deterministic rules handle immediate safety and timing-critical coaching, while slower model-based reasoning is reserved for enrichment and post-session review.

## Runtime Flow

1. Live telemetry enters through Android field sources such as AiM CAN USB, phone GPS/IMU, RaceBox, or OBD-backed fusion.
2. The app normalizes each sample into a `TelemetryFrame`.
3. The hot path applies deterministic coaching rules, track phase detection, timing gates, and driver-state adaptation.
4. Optional edge/cloud reasoning can enrich non-critical guidance without blocking immediate cues.
5. Sessions are recorded as replayable sidecar files for later inspection.

## Coaching Model

The coaching layer is modular. Raw candidate actions are routed through corner and phase objectives before any final cue priority is selected. This prevents generic advice such as braking for every turn, keeps safety cues reserved for true emergency cases, and lets track-specific objectives shape line, patience, maintenance throttle, and exit-throttle guidance.

## Field Data Path

For VBOX-derived vehicle data, the app continues to use the existing `AIM_CAN_USB` telemetry source. VBOX parity is represented only as metadata derived from the ingested speed channel: the app records the default moving trigger threshold, pre-roll window, and below-threshold stop window so Racecraft sessions can be aligned with external VBOX video later.

## Persistence And Replay

Field sessions are streamed to bounded sidecar files:

- `session.json`
- `frames.ndjson`
- `decisions.ndjson`
- `audio-events.ndjson`
- `can-slcan.txt`

This keeps long runs from growing unbounded in memory and gives the replay/analysis tools stable artifacts for later review.

## Safety Constraints

- P0 safety coaching never waits for model output.
- Mid-corner/apex blackout prevents distracting non-critical cues.
- No-live-data telemetry states suppress coaching.
- Audio attempts and suppressions are recorded for auditability.
- Generated submission material, private docs, and PDF references are intentionally kept out of the public repository.
