# Current Implementation Audit

**Owner:** Rabimba  
**Last updated:** April 28, 2026  
**Purpose:** Compare the current implementation in this repo against the original product documents and capture what is actually shipped today, what has improved beyond the original concept, and what still needs to be built.

## Reference Docs

This audit was evaluated against:

- Original user stories: `https://github.com/ykro/trustable-ai-codelab/blob/main/docs/user-stories.md`
- Pre-race chat contract: `docs/pre-race-chat-contract.md`
- Real-time reasoning learnings: `docs/learnings-real-time-data-reasoning.md`

## Executive Summary

The repo has moved well beyond the original browser-first prototype. The biggest upgrade is that the project now includes a real native Android runtime with:

- a foreground telemetry service
- on-device camera analysis
- fused telemetry + vision frames
- recorded coaching sessions
- Sonoma-specific coaching doctrine
- Gemini-powered post-session analysis

The architecture is materially stronger than the original concept, but it is not fully complete against the original documents.

The main gaps are:

- no live RaceBox BLE or OBD telemetry yet
- no proper session library/archive yet
- no raw video captured into the saved session artifact
- no durable driver-profile loop yet

## What You Own Now

You now own a native-first race coaching prototype with these working pieces:

- A native Android app that can run offline on device and host the web UI in WebView.
- A live telemetry lane with three modes:
  - `Telemetry + Camera Fusion`
  - `Device Camera + GPS Test`
  - `Camera Feedback (Debug)`
- A real on-device phone telemetry source:
  - `phone_imu_gps`
- A telemetry source abstraction ready for future hardware inputs:
  - `synthetic`
  - `phone_imu_gps`
  - `racebox_ble` placeholder
  - `obd_bluetooth` placeholder
- A camera analysis lane that produces derived vision signals and attaches them to live telemetry frames.
- An on-device reasoning pipeline that can produce live spoken coaching.
- A Sonoma-specific knowledge layer based on T-Rod coaching and Ross Bentley pedagogy.
- A saved fused session artifact with frames and coaching decisions.
- Replay and post-session Gemini analysis over saved sessions.

In plain terms: you have a real working prototype that can coach in real time on a phone, save the session, and analyze it later. What you do not yet have is final race-grade telemetry fidelity or a complete session-management product.

## Audit Against Original User Stories

### UX-1 — Offline Live HUD

**Original intent:** A live dashboard that stays usable during a drive, works with bad or no network, and does not depend on cloud connectivity.

**Status:** `Partial, but improved beyond the original implementation plan`

**What is done**

- The project now has a native Android app instead of only a browser session.
- Live telemetry can run in an Android foreground service.
- The on-device runtime can continue without live network access.
- The camera lane and phone telemetry lane both work on device.

**What improved**

- The original story assumed a phone-based live experience. The current implementation goes further by making it native Android instead of depending only on a browser tab.

**What is still missing**

- There is no web PWA/offline service-worker path.
- There is no explicit wake-lock/no-sleep implementation in the browser UI.
- The original "works even if the phone dims/backgrounds" story is much stronger on Android than on the browser side.

### UX-2 — Driver Coach Roster Clarity

**Original intent:** Show 5 distinct AI coaches, with clear style differences, and let the user switch easily.

**Status:** `Mostly done`

**What is done**

- Five coaches exist in the product:
  - Tony
  - Rachel
  - AJ
  - Garmin
  - Super AJ
- Each coach has a different style and prompt profile.
- The coach panel lets the driver switch coaches directly.
- Each coach shows a visible style label.

**What is still missing**

- The UI does not yet show a dedicated sample phrase preview before session start.
- The product does not yet explain which coach is best for which driver or scenario.

### UX-3 — Minimal Real-Time Coaching Signal

**Original intent:** Give the driver immediate live feedback instead of waiting until the end of the lap.

**Status:** `Done, and improved`

**What is done**

- The live system can emit coaching in real time.
- The system supports:
  - hot path cues
  - feedforward cues
  - edge/on-device reasoning cues
- Live spoken feedback now works in native telemetry mode and camera-debug mode.

**What improved**

- The current system is stronger than the original "good / needs work / cue" idea.
- It now includes priority, timing gates, skill adaptation, and fused camera + telemetry in native mode.

**What still needs work**

- The signal is realtime, but it is not yet final race-grade coaching because live car telemetry is still missing.

### UX-4 — End-of-Session Export

**Original intent:** Save a session and later re-import it into analysis.

**Status:** `Partially done, and improved`

**What is done**

- Native Android saves a recorded session artifact to app storage.
- The saved artifact contains:
  - session metadata
  - fused telemetry frames
  - vision features
  - coaching decisions
- Replay can load the latest recorded session.
- Analysis can use saved session artifacts for Gemini review.

**What improved**

- The current saved artifact is richer than a plain export file because it already includes fused reasoning context and decisions.

**What is still missing**

- The web app only stores the latest captured session in `localStorage`.
- There is no proper session archive or multi-session library.
- There is no raw video bound into the saved session.

### UX-5 — Coach Recommendation Before Going Live

**Original intent:** Help the user choose the right coach, or recommend one automatically.

**Status:** `Done`

**What is done**

- The user can choose a coach before going live.
- The pre-race setup now recommends a coach based on session goals and session mode.
- The UI shows both a rationale and a sample cue before session start.

**What is still missing**

- The recommendation is still heuristic rather than driven by a long-term driver profile.

## Audit Against Pre-Race Chat Contract

### Contract Status

**Overall status:** `Implemented, with future extensions still open`

### What is implemented

- `SessionGoal` exists as a typed contract.
- `CoachingService.setSessionGoals(goals)` exists.
- The max-3-goal rule is implemented.
- Tests exist for storing goals and enforcing the max.
- There is now a pre-race goal-selection workflow in the live session UI.
- Goals are passed into both browser and native live-session configs.
- `prioritizedActions` now bias hot-path action selection in both browser and native runtimes.
- Saved session artifacts now persist the active goals for replay and Gemini analysis.

### What is not implemented

- There is no auto-generated goal flow from a driver profile.
- There is no coach-assigned goal flow.

## Audit Against Real-Time Data Reasoning Learnings

### Split-brain architecture

**Status:** `Implemented, but split across web and native paths`

**What is done**

- The browser coaching engine implements:
  - hot path
  - cold path
  - feedforward
- The native Android runtime implements:
  - hot path
  - feedforward
  - edge/on-device reasoning
- Replay and Analysis implement the deeper Gemini review path for saved sessions.

**Important note**

The original learnings doc described hot and cold paths as part of one broader reasoning pattern. Today, the product still follows that idea, but it is spread across two places:

- live native coaching for immediate action
- replay/analysis for slower Gemini review

So the architectural idea is preserved, but the cold path is stronger post-session than it is during live native driving.

### Priority and timed delivery

**Status:** `Implemented`

**What is done**

- Priority queueing exists.
- Safety messages can preempt.
- Messages expire when stale.
- Timing gate handles blackout and cooldown.
- Skill level changes cooldown and blackout behavior.

### User model and adaptive coaching

**Status:** `Implemented`

**What is done**

- Driver skill level is inferred from telemetry behavior.
- Cognitive load and smoothness affect delivery.
- Humanization changes by skill level and coach persona.

### Session goals as a filter

**Status:** `Implemented`

**What is done**

- Data contract exists.
- The live rule engine now uses active goals to break ties within the same urgency band.
- Goal context is also preserved in the saved session for cold-path analysis.

**What is missing**

- There is not yet a persistent driver profile that can auto-suggest or refine goals over time.

### Improvement tracking

**Status:** `Partially implemented`

**What is done**

- Performance tracking exists.
- Replay can analyze saved sessions.
- Gemini review can process a full recorded session artifact.

**What is still missing**

- Cross-session persistence is still an interface, not a full product feature.
- There is no durable driver profile loop feeding future live sessions.

### Mixed data rates / streaming telemetry thinking

**Status:** `Partially implemented`

**What is done**

- The architecture clearly expects multiple telemetry sources.
- The native app now supports a phone GPS/IMU source and a source abstraction for future hardware.

**What is still missing**

- The real mixed-source live-car telemetry case is not complete until RaceBox and OBD are implemented.

## Architectural Improvements Beyond the Original Concept

These are meaningful upgrades beyond the original docs.

### 1. Native Android runtime

The original concept was mostly framed around the web coaching engine. The current implementation adds a real native app with foreground telemetry execution and on-device audio dispatch.

### 2. Camera fusion

The original docs focused mainly on telemetry reasoning. The current system now includes a camera analysis lane and attaches vision-derived features to telemetry frames.

### 3. Device-only test lane

You now have a clean test mode for on-device validation using just the phone:

- phone camera
- phone GPS
- phone IMU

This is useful for latency and reasoning-path testing before hardware telemetry arrives.

### 4. Sonoma-specific doctrine

The product is no longer only generic. It now includes Sonoma deployment logic and a Sonoma-specific coaching doctrine layer shaped by T-Rod notes and Ross Bentley pedagogy.

### 5. Better session artifact

The current recorded session artifact is not just raw telemetry. It already includes:

- fused frames
- camera-derived vision features
- coaching decisions

That is a stronger base for later post-session analysis.

### 6. Gemini cold-path upgrade

Post-session analysis now runs on the newer Gemini cloud stack and can analyze full saved sessions instead of only a small local replay window.

## What Still Needs To Be Done

This is the practical next-build list.

### 1. Finish live car telemetry

Highest priority.

- Implement `RaceBoxBleSource`
- Implement `ObdBluetoothSource`
- Make real hardware telemetry the default fused driving lane

This is the biggest remaining gap between prototype and race-ready system.

### 2. Build a true session archive

- Store multiple sessions, not just the latest one
- Show session cards in the UI
- Support reloading prior sessions for replay and Gemini review
- Store cloud analysis results with the session

### 3. Add raw video to the recorded artifact

- Record synchronized camera video
- Save a file reference alongside the fused session artifact
- Let replay and Gemini review use both:
  - sensor trace
  - original footage

### 4. Close the hot/cold architecture loop

- Keep the on-device live path fast and local
- Keep Gemini for deeper post-session analysis
- Optionally add a non-blocking live cloud advisory lane later, but never let it block the hot path

### 5. Build the driver profile loop

- Persist session summaries
- Identify recurring weaknesses by corner/action
- Use that profile to:
  - suggest goals
  - pick a coach
  - bias future live coaching

## Bottom Line

The original idea is no longer just a browser coaching prototype. It has evolved into a native-first, on-device race coaching system with fused telemetry and camera reasoning, Sonoma-specific coaching knowledge, and saved-session Gemini analysis.

What is fully real today:

- native Android live runtime
- realtime coaching
- phone GPS/IMU telemetry
- camera fusion
- saved fused session artifacts
- Sonoma knowledge layer
- Gemini post-session analysis

What is still scaffolded rather than complete:

- RaceBox BLE
- OBD telemetry
- session archive
- synchronized raw video
- long-term driver-profile automation

That is the clearest description of what this project is now, and what still needs to be built next.
