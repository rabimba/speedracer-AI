# Racecraft — Trustable AI Race Coach

> Repo: [`github.com/rabimba/speedracer-AI`](https://github.com/rabimba/speedracer-AI) | Live site: [`rabimba.github.io/speedracer-AI`](https://rabimba.github.io/speedracer-AI/)
>
> **Blog series:** [Racecraft / Project Koru — Prologue (Origin)](https://rkrants.blogspot.com/2026/06/racecraft-project-koru-prologue-origin.html) · [Bridging the Domain Gap: AI Race Coach Built with Antigravity and Gemini (Google Developers Blog)](https://developers.googleblog.com/bridging-the-domain-gap-ai-race-coach-built-with-antigravity-and-gemini/)

A real-time, on-device driving coach that processes telemetry streams and delivers context-aware coaching as you drive — not after the fact. Built on a split-brain architecture: deterministic rules for sub-50ms safety cues, grounded LLM analysis for strategic feedback, and reference-trace delta coaching that explains the gap to a gold lap.

```
Catalyst tells you what you did wrong with numbers.
Racecraft tells you how to fix it in real time, adjusted to your skill.
```

### Download the Android APK

Grab the latest APK from the [Releases page](https://github.com/rabimba/speedracer-AI/releases) — download `racecraft-latest.apk` and install on a Pixel device (Android 10+ / API 29+). Enable "Install from unknown sources" in Settings if prompted.

> The APK is built automatically on every tagged release (`v*` tags trigger the [Release workflow](.github/workflows/release.yml)).

## What's Built

| Capability | Status |
|---|---|
| **HOT path** — 12 deterministic threshold rules, P0 safety preemption, <50ms | Production |
| **DELTA path** — reference-trace comparison at corner exit, evidence-based cues | Production |
| **COLD path** — Gemini 2.5 Flash Lite, corner-aware trigger, structured JSON, confidence routing, retry/backoff | Production |
| **FEEDFORWARD** — velocity-scaled geofence (120-320m, 4-5s lead), corner setup advice | Production |
| **EDGE path** — on-device Gemma 4 E2B via LiteRT-LM, 750ms inference timeout (Android only) | Field-test |
| **Driver model** — skill classification (BEGINNER/INTERMEDIATE/ADVANCED), adaptive timing | Production |
| **Readiness progression** — session phase advances on telemetry signals, not wall clock | Production |
| **Driver profile store** — cross-session corner performance, problem corners, auto-goals | Production |
| **Corner doctrine** — property-keyed expertise (brakeZone, exitPriority, maintenance, sacrifice, doubleApex) | Production |
| **Track support** — Sonoma Raceway + Thunderhill East (15 corners, GPS-anchored) | Production |
| **GitHub Pages** — auto-deploy on push to main | Production |

**Tests:** 157 (core-telemetry 30 + koru 127). Build + lint clean.

## Architecture

```
  TelemetryFrame
       │
       ├──► HOT PATH          Deterministic rules → instant safety cues (<50ms)
       │                       "Brake!" "Trail off!" "Countersteer!"
       │
       ├──► DELTA PATH         Reference-trace comparison at corner exit (P2)
       │                       "Turn 1: Carry more speed next" (14 mph below reference)
       │
       ├──► COLD PATH          Gemini Flash Lite, corner-aware, structured JSON (P2)
       │                       {"cause":"overbraking","fix":"Brake earlier, release to apex","confidence":0.82}
       │
       ├──► FEEDFORWARD        Velocity-scaled geofence, corner setup advice (P1)
       │                       "📍 Turn 3: late apex, wait for the curb."
       │
       └──► EDGE (Android)     On-device Gemma 4 E2B, non-blocking enrichment (P2)
                                Single-flight queue, 750ms timeout, never blocks HOT/P0
```

All paths feed a priority queue (P0-P3) gated by a timing state machine (OPEN → DELIVERING → COOLDOWN → BLACKOUT). P0 preempts everything. Blackout silences non-P0 during mid-corner/apex.

## Coaching Intelligence

### Reference-Trace Delta Coaching (DEL)
Gold reference traces for Sonoma and Thunderhill. At corner exit, the system compares the driver's apex frame against the nearest reference sample and generates an evidence-based cue with the gap quantified. Enriches decisions with `objective`, `causeId`, `confidence`, `cornerId`, `cornerName`.

### Grounded COLD Path
Fires at corner exit (not arbitrary frames). Prompt includes DEL delta evidence, track context for all tracks, and T-Rod coaching insights. Requests structured JSON `{cause, fix, confidence}`. Low-confidence responses (<0.6) are discarded — the deterministic delta cue stands instead. Retry with exponential backoff on 5xx, fail fast on 4xx.

### Corner Doctrine
Corners are tagged with properties (`brakeZone`, `exitPriority`, `maintenance`, `sacrifice`, `doubleApex`) instead of hardcoded IDs. Any track with tagged corners gets doctrine-aware coaching — suppression, text generation, and objective mapping — without writing track-specific code.

### Readiness-Based Progression
Session phase (1-3) advances when the driver demonstrates readiness via telemetry signals (corner count, brake smoothness, throttle commitment), not when 60 seconds elapse. Aligns with T-Rod's pedagogical model: lines → shifts → trail braking → throttle commitment.

### Driver Profile
Cross-session corner performance persists to localStorage. Problem corners (issue count ≥ 3 across sessions), strengths, and weaknesses are computed. Session goals auto-generate from the profile — e.g., "Focus on Turn 1 brake — recurring problem corner from previous sessions."

### Coach Personas

| Coach | Style | Example |
|-------|-------|---------|
| **Tony** | Motivational | "Commit! Trust the grip!" |
| **Rachel** | Technical | "Trail off brake before turn-in. Balance the platform." |
| **AJ** | Direct | "Brake 5m later." |
| **Garmin** | Data | "Entry speed: -8 mph vs ideal. +0.3s potential." |
| **Super AJ** | Adaptive | Switches style per error type |

## Repo Structure

```
koru-application/          React + TypeScript web app (Vite, Tailwind)
  src/services/
    coachingService.ts     Split-brain engine (HOT/DELTA/COLD/FEEDFORWARD)
    driverProfileStore.ts  Cross-session persistence (localStorage)
    readinessEvaluator.ts  Telemetry-based session phase progression
    performanceTracker.ts  Per-corner lap-over-lap tracking
    sharedPhraseCatalog.ts JSON phrase catalog (shared with Android)
  src/data/
    trackData.ts           Re-export from @trustable/core-telemetry
    trackExpertise.ts      Doctrine-keyed coaching layer, T-Rod insights
    trodCoachingData.ts    Extracted from real Tony Rodriguez coaching session

packages/core-telemetry/   Shared TypeScript library
  src/tracks/              Sonoma + Thunderhill track definitions
  src/corner/              CornerPhaseDetector (GPS + G-force)
  src/del/                 Delta analysis, biometric load, brake degradation, learning plans
  src/traces/              Gold reference traces (Sonoma + Thunderhill)

pixel-android-app/         Native Android (Kotlin + Jetpack Compose + Material 3)
  app/src/main/.../runtime/
    KoruRealtimeEngine.kt  Android coaching engine (HOT/FEEDFORWARD/EDGE)
    DeterministicCore.kt   Corner detector, timing gate, queue, driver model, edge triggers
    CornerDoctrineCatalog.kt  Doctrine-keyed coaching objectives + text
    TrackCatalog.kt        Sonoma + Thunderhill corner data
    reasoner/              LiteRT-LM (Gemma 4), AICore (scaffold), deterministic fallback

streaming-telemetry-server/  Python FastAPI SSE server (mock + serial GPS)
.github/workflows/           CI (test/lint/build) + Deploy (GitHub Pages)
```

## Onboarding

### Prerequisites
- Node.js 22+ (npm 11+)
- Python 3.10+ (for telemetry server)
- [Gemini API key](https://aistudio.google.com/apikey) (optional — hot path works without it)

### Web App

```bash
npm install                # from repo root (monorepo)
cd koru-application
npm run dev                # http://localhost:5173
```

### Telemetry Server (optional, for live SSE)

```bash
cd streaming-telemetry-server
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
python ingest.py --mock    # replays Sonoma CSV at 10Hz
```

### Android App (optional, for on-device testing)

```bash
cd koru-application
npm run pixel:e2e:prepare   # build web + stage model
```

Then open `pixel-android-app/` in Android Studio, connect a Pixel device, run the `app` module.

### Gemini (optional)

Click the gear icon in the navbar → paste API key. Enables:
- COLD path cloud coaching (Gemini 2.5 Flash Lite)
- Post-session AI lap comparison
- Gemini TTS voice output

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Web frontend | React 19 + TypeScript + Vite 8 + Tailwind 4 |
| Shared library | `@trustable/core-telemetry` (Vitest, 30 tests) |
| Native Android | Kotlin + Jetpack Compose + Material 3 + CameraX |
| On-device AI | Gemma 4 E2B via MediaPipe LiteRT-LM (GPU) |
| Cloud AI | Gemini 2.5 Flash Lite (`@google/genai`) |
| Telemetry server | Python FastAPI + SSE |
| CI/CD | GitHub Actions (test/lint/build + Pages deploy) |
| Tests | 157 total (Vitest + JUnit) |

## License

MIT
