# Implementation History and Design Log

**Owner:** Rabimba  
**Last updated:** April 28, 2026  
**Purpose:** Living document for what has been implemented in this repo, the design decisions behind it, how the architecture changed over time, and what remains to be built.

## 1. Product Goal

The original idea was a race coach that can:

- guide the driver in real time
- stay useful even when cloud connectivity is weak
- combine track knowledge, telemetry, and coaching pedagogy
- save each coaching session for later deeper analysis

The current implementation is now materially beyond the original browser-first concept. The project now includes a native Android runtime, on-device reasoning, saved coaching artifacts, Sonoma-specific doctrine, and a clearer hot/cold path split.

## 2. Architecture North Star

The architecture now follows this core shape:

`live sensors -> fused session frame -> realtime coaching -> saved session artifact -> replay/post-run analysis`

More specifically:

- live telemetry provides the main timebase
- live camera analysis provides vision features attached to the telemetry frame
- the hot path stays on device for low-latency coaching
- the cold path uses saved sessions for deeper Gemini analysis
- track knowledge and coach doctrine sit between raw data and final phrasing

This is a deliberate shift away from a prompt-only architecture. The system is now rule-guided and doctrine-guided first, with models used where they add value rather than where they would create ambiguity.

## 3. Major Implementation Milestones

### Milestone 1: Browser-first live coaching prototype

The first working shape of the product lived in the browser app:

- live telemetry-driven coaching loop
- split-brain coaching service
  - hot path
  - cold path
  - feedforward path
- coach roster and style switching
- replay and analysis pages

This established the product behavior, but it still had the main weakness of a browser-bound runtime.

### Milestone 2: Native Android app and WebView host

The project was then moved into a native Android app that hosts the web UI and provides native capabilities.

Implemented:

- native Android shell around the web experience
- JS bridge between WebView and Android
- Android-side live session startup and control
- local bundled web assets inside the app

Why this decision was made:

- Android gives much stronger control over camera, location, foreground execution, and saved artifacts than a browser-only path
- it is also the right base for on-device coaching without depending on cloud latency

### Milestone 3: Native live runtime and session modes

The live runtime was then separated into distinct lanes:

- `Telemetry + Camera Fusion`
- `Device Camera + GPS Test`
- `Camera Feedback (Debug)`

Why this split exists:

- `Telemetry + Camera Fusion` is the intended production lane
- `Device Camera + GPS Test` is the device-only validation lane when no real car telemetry is available
- `Camera Feedback (Debug)` is a controlled debug lane for vision-only work and on-device model testing

This prevents product testing, sensor testing, and vision debugging from being mixed together.

### Milestone 4: Camera lane and vision feature fusion

Implemented:

- CameraX preview and analysis
- vision feature extraction
- live vision feature store
- fusion of the latest camera snapshot into the active telemetry frame

Current fusion pattern:

- telemetry is the master clock
- each telemetry tick pulls in the latest vision snapshot
- the fused `TelemetryFrame` becomes the unit of reasoning

Why this decision was made:

- it keeps the live loop deterministic
- it avoids the complexity of trying to make camera frames the primary event clock
- it matches the original data-reasoning direction better than a separate vision-only coach

### Milestone 5: Realtime native reasoning engine

Implemented:

- native `KoruRealtimeEngine`
- deterministic hot path
- feedforward path
- edge reasoning path
- timing gate and coaching queue
- skill adaptation

What this means:

- the driver can get live coaching on device without waiting for cloud analysis
- messages are prioritized and timed instead of being spoken every frame
- the product now behaves more like a coach and less like a stream of alerts

### Milestone 6: On-device model path and graceful fallback

Implemented:

- on-device runtime selection
- LiteRT/AICore warmup path
- deterministic fallback when on-device model warmup is unavailable

Why this matters:

- the system can keep coaching even if the edge model path is unavailable
- this preserves the live session instead of failing the whole drive

### Milestone 7: Real native telemetry abstraction

Implemented:

- telemetry source abstraction
- selectable telemetry sources
- source metadata carried through the live session

Current source list:

- `synthetic`
- `phone_imu_gps`
- `racebox_ble` placeholder
- `obd_bluetooth` placeholder

This is one of the most important structural upgrades in the repo because it gives a stable seam for moving from simulation to real hardware.

### Milestone 8: First real telemetry source with phone IMU + GPS

Implemented:

- `phone_imu_gps` as the first real native telemetry source
- location permission and Android location foreground-service flow
- motion gating so parked testing does not speak like a live lap

What this source provides today:

- GPS-based position and speed
- phone IMU motion signals
- inferred driving state for bench and basic live testing

What it does not provide yet:

- true throttle
- true brake pressure
- true wheel speed
- steering angle
- engine CAN/OBD fidelity

So this is a real telemetry source, but not yet final race telemetry.

### Milestone 9: Device-only test lane

Implemented:

- `Device Camera + GPS Test`
- no pre-baked track requirement
- camera + phone GPS/IMU realtime path

Why it exists:

- it allows full on-device validation without RaceBox or OBD
- it gives a clean way to test latency, stability, and prompt quality on the phone itself

### Milestone 10: Saved session artifacts and replay path

Implemented:

- native saved session artifact
- recorded fused telemetry frames
- recorded coaching decisions
- replay loading of the latest native capture
- post-session Gemini analysis over saved artifacts

Important design decision:

- the recorded artifact is now the product source of truth for post-run analysis
- replay and Gemini do not need to reconstruct the session from scattered browser state

What is still missing:

- persistent session archive
- raw bound video in the same saved artifact

### Milestone 11: Sonoma deployment and coaching doctrine

Implemented:

- Sonoma as the main deployment track
- Sonoma sector and corner knowledge in the product
- domain expertise layer based on:
  - T-Rod Sonoma coaching notes
  - Ross Bentley coaching pedagogy

Doctrine is now used for:

- feedforward
- hot-path suppression
- hot-path wording
- track-specific coaching context

Examples of doctrine behavior:

- Turn 3 suppresses premature throttle advice before the late-apex exit
- Turn 6 prioritizes distance and maintenance-throttle behavior over generic hustle
- high cognitive load suppresses more aggressive prompts and shifts to simpler coaching

Why this matters:

- the coach now behaves more like an instructor with a method
- track knowledge is enforced by the runtime rather than left to model guesswork

### Milestone 12: Pre-race goals and coach recommendation

Implemented:

- pre-race setup panel
- up to three session goals
- custom goal support
- coach recommendation with rationale and sample cue
- goal propagation into native and browser live-session configs
- recorded session goals saved into the session artifact

Also implemented:

- goals now bias hot-path action selection
- goal-prioritized actions win within the same urgency band

Why this matters:

- the system no longer treats every session as generic
- the same live signal can now be interpreted differently depending on what the driver is trying to work on

### Milestone 13: Realtime delivery hardening

Implemented:

- native live audio policy
- minimum motion gate for spoken coaching
- minimum confidence gate for weak edge cues
- faster advanced-driver timing profile
- interruption of lower-priority TTS when a higher-priority cue arrives

Current speech behavior:

- safety-critical prompts still bypass normal delay behavior
- weak or low-speed cues do not speak
- advanced mode now cycles faster and blacks out less
- queued lower-priority speech can be interrupted by a higher-priority message

Why this matters:

- it gets the product closer to racer-usable behavior instead of generic screen notifications

## 4. Hot Path and Cold Path

### Hot path

The hot path is now working mainly in native Android live sessions.

Current shape:

- telemetry source or camera lane produces a frame
- latest camera snapshot is fused when appropriate
- `KoruRealtimeEngine` evaluates hot, feedforward, and edge paths
- a priority/timing gate controls delivery
- audio and UI update immediately

This is the realtime layer.

### Cold path

The cold path is now centered around saved session artifacts and Gemini.

Current shape:

- native session finishes
- saved artifact includes frames, decisions, metadata, and goals
- Replay/Analysis can use the artifact for deeper review
- Gemini sees more than a tiny live window and can reason over the full session summary and decision trace

This is the deeper coaching and review layer.

Design decision:

- the cold path should not compete with the live path during a drive
- it should explain, summarize, compare, and teach after the session

## 5. Key Design Decisions

### 5.1 Telemetry is the master clock

I chose telemetry as the main live timebase and attached the latest camera features to it.

Why:

- easier synchronization
- cleaner replay
- better path to RaceBox/OBD integration

### 5.2 Doctrine is structured, not only prompt text

I did not leave T-Rod and Ross Bentley as plain prompt context.

Instead:

- track doctrine is encoded into runtime logic
- prompts can be rewritten or suppressed by doctrine
- the model is not trusted as the source of truth for coaching method

### 5.3 Camera-only is a debug lane, not the main product lane

I intentionally preserved `camera_direct`, but kept it labeled as a debug lane.

Why:

- it is useful for on-device reasoning tests
- it is not enough for race-grade coaching on its own

### 5.4 Saved session artifact is the analysis contract

Post-session review should use a stable artifact instead of transient page state.

That is why the recorded session bundle now carries:

- session metadata
- track
- coach
- mode
- telemetry source
- goals
- frames
- decisions

This will later be extended with video and archived reports.

## 6. What Works Now

Today the repo supports:

- a native Android live coaching app
- realtime camera-only debug coaching
- realtime telemetry + camera fusion coaching
- device-only phone GPS/IMU + camera testing
- Sonoma-specific track doctrine
- T-Rod and Ross Bentley guided coaching behavior
- pre-race goal selection
- coach recommendation before a session
- saved fused session artifacts
- Replay and Gemini-based post-session analysis

## 7. What Still Needs to Be Done

The main unfinished product work is:

- real `RaceBox BLE` telemetry ingestion
- real `OBD Bluetooth` telemetry ingestion
- persistent session archive instead of latest-only replay storage
- raw video recording tied to the same session artifact
- synchronized multimodal replay
- stored post-session Gemini reports attached to each archived session
- stronger racing semantics from vision
- more precise track-aware fusion once real telemetry is available

## 8. Recommended Next Build Order

The next steps should be:

1. Persistent session archive
- session index
- session browser
- multi-session retrieval

2. Raw video binding
- recorded video file path/URI added to the session artifact
- timestamp alignment with telemetry and decisions

3. Real hardware telemetry
- RaceBox first
- OBD second

4. Session review productization
- Gemini report attached to saved session
- archive UI for opening old sessions and reports

## 9. Current Ownership Summary

Right now you own:

- a native-first race-coaching prototype
- a structured live architecture with clear test and product lanes
- a Sonoma deployment path with doctrine
- a saved-session review flow

You do not yet own:

- final race-grade hardware telemetry fidelity
- full multimodal archived replay
- a production-quality session library

## 10. How to Maintain This Document

Update this file after any major architecture change, especially when one of these happens:

- a new live session mode is added
- a telemetry source moves from placeholder to real
- a new doctrine layer is introduced
- saved session artifacts change shape
- the live/cold reasoning contract changes

This file is intended to be the running product and architecture history, not just a one-time audit.
