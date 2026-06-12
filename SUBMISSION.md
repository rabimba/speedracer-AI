# Racecraft — Submission

**A trustable, on-device, real-time driving coach built around Gemma 4.**
Repo: https://github.com/rabimba/speedracer-AI · Snapshot: 2026-06-08

Racecraft coaches a driver *as they drive* — choosing what to say, in whose voice, and
(most importantly) **when to stay silent** — entirely from a phone in the car. The
guiding constraint is a single sentence: **"feedback 800 ms late is worse than
silence."** Everything in the architecture follows from refusing to violate it.

It was built and road-tested across three skill-tier teams at Thunderhill and Sonoma.
This work is the **beginner-coaching** app; the author both led that team and was the
test driver in a Toyota GR86 — and the same engine adapted cleanly to the intermediate
car, the first evidence that the design is skill- and domain-agnostic. The design model
throughout is the mandatory track instructor: allowed to *advise*, never to touch a
control, and never to become a distraction.

---

## 1. What we achieved (headline, all measured on hardware)

Every number below traces to a checked-in artifact or a command in §5 — nothing is
estimated.

| Achievement | Result | Evidence |
| :--- | :--- | :--- |
| **On-device LLM token generation, all 3 accelerators** | Measured via LiteRT-LM on a Pixel 10 | `submission-artifacts/benchmarks/accelerator-comparison-report.json` |
| **NPU is the fastest lane** | **14.3 tok/s, 424 ms TTFT** (Tensor G5) | same report |
| GPU / CPU lanes | 10.3 tok/s @ 1108 ms / 6.9 tok/s @ 8545 ms TTFT | same report |
| **Deterministic safety path latency** | **HOT p95 = 5.00 ms** (budget 50 ms) | `sonoma-training-e2e/reports/20260503-220239` |
| **Spoken P0 alert dispatch** | **max = 5.00 ms** (budget 100 ms) | same recorded run |
| **On-device end-to-end validation** | **16/16 checks**, `OK (1 test)` on Pixel 10 / Android 16 | same run + `tools/validate-report.mjs` |
| Telemetry parser | 4/4 unit tests pass | `streaming-telemetry-server/test_nmea_parsing.py` |

**The story in one line:** the safety-critical lane runs in **5 ms** with no model in
it, while the *smart* lane runs a real **Gemma 4 E2B** model on the phone's **NPU** in
**424 ms to first token** — and the two never block each other.

### Why time-to-first-token is the headline, not throughput

For a coach, the metric that decides whether on-device reasoning is usable is
**TTFT** — how long before the first word arrives. The NPU's **424 ms** vs the CPU's
**8.5 s** is the difference between enrichment that feels present and enrichment that
arrives after the corner is over. The efficient silicon matters here for *latency to
first word*, not for peak throughput bragging rights.

---

## 2. Technical architecture

### The Split-Brain engine
Every fused telemetry frame fans out to three asynchronous lanes against a 300–500 ms
budget:

- **HOT (<50 ms, measured 5 ms p95):** a hand-written, auditable `DECISION_MATRIX` of
  heuristics. Fires P0 safety ("Brake!") and P1 tactical cues with **no network and no
  LLM**. Chosen deliberately *dumb* so it has no tail latency and so a human can read
  the rule that fired any safety alert.
- **EDGE (on-device, Gemma 4 E2B):** tactical enrichment via **LiteRT-LM**, now
  measured on GPU/CPU/**NPU**. Runs in a single-flight async queue; never on the hot
  thread.
- **COLD (2–5 s, cloud):** **Gemini 2.5 Flash Lite** for "why, not what" strategy. The
  loop never waits on it.
- **FEEDFORWARD (geofenced):** velocity-scaled, GPS-triggered, ~150 m before a corner.

Two referees keep it sane: the **CoachingQueue** (a P0 alert `preempt()`s everything)
and the **TimingGate** (a `CornerPhase` state machine that enforces silence during peak
cognitive load like the apex).

### The on-device model & the NPU path
The EDGE model is **Gemma 4 E2B** as a `.litertlm` artifact. The benchmark harness
(`LlmBenchmarkRunner.kt`) does a warmup pass, captures TTFT off the streaming callback,
and reports median/p95 tok/s and latency. The **NPU lane** runs a Tensor-G5–specific
artifact (`gemma-4-E2B-it_Google_Tensor_G5.litertlm`) through the packaged
`libLiteRtDispatch_GoogleTensor.so` dispatch runtime; each accelerator is benchmarked
in its **own instrumentation process** so a native dispatch failure is recorded per
lane instead of aborting the run.

### The coaching paradigm (coach the human, not the car)
The **DriverModel** classifies the driver as BEGINNER / INTERMEDIATE / ADVANCED on a
10-second rolling window with 5-second hysteresis, from **input smoothness** (jerk) and
**coasting ratio**. That one label modulates four things: coaching *language*
(feel-based vs. data), *cadence* (3000 ms cooldown + apex blackout vs. 650 ms),
*progression* (advanced techniques suppressed early), and the *LLM prompt*. A persona
layer (AJ/Tony/Rachel) sets the voice.

### Trust through determinism
The HOT path is human-readable rules, so its safety behavior is auditable in a way a
sampled LLM never can be. Sessions persist the full fused-frame timeline, decisions,
and **audio-dispatch latency evidence**, so any lap can be replayed and verified — which
is exactly what the on-device validator reads to produce the 16/16 result.

---

## 3. Novelty

- **A deterministic safety lane that shares no thread with any model**, so the system is
  safe to ship even when the model lane is blocked, slow, or absent.
- **A genuine three-way on-device accelerator comparison (GPU/CPU/NPU) for an LLM**, with
  an honest harness that reports PENDING rather than fabricating numbers when a model
  won't load.
- **Real-time skill-adaptive coaching** driven by input dynamics, not lap time.
- **A domain-agnostic core**: the Split-Brain + queue + timing-gate pattern generalizes
  to any high-stakes real-time guidance problem where "right but late" equals "wrong."

---

## 4. Honest limitations

- The **`AiCoreReasoner`** (AICore Prompt API) lane remains a reflective *health probe*,
  not the primary generator; the measured NPU result above is the **LiteRT-LM Tensor
  dispatch** path. These are kept distinct on purpose.
- **GPU full-generation latency** (42 s) reads higher than CPU (29 s) despite higher
  tok/s because output lengths differ per run; compare lanes on **tok/s and TTFT**, not
  total latency, until `maxTokens` is pinned equal.
- The **web app unit tests (vitest)** were not executed in the build sandbox (an
  arch-mismatched native binding); a clean `npm ci` runs them on a normal machine.
- The earlier P0 safety alert is still persona-styled; a global persona-override "safety
  voice" is future work.

---

## 5. Reproduce it

```bash
# Telemetry parser unit tests (host)
cd streaming-telemetry-server && python3 -m unittest test_nmea_parsing -v

# On-device end-to-end validation (against the recorded Pixel 10 run)
node sonoma-training-e2e/tools/validate-report.mjs \
  --artifact sonoma-training-e2e/reports/20260503-220239/recorded-session.json \
  --logcat   sonoma-training-e2e/reports/20260503-220239/logcat.txt \
  --instrumentation sonoma-training-e2e/reports/20260503-220239/instrumentation.txt \
  --metadata sonoma-training-e2e/reports/20260503-220239/metadata.json --out /tmp/val

# Real GPU vs CPU vs NPU benchmark (Pixel connected + unlocked)
./submission-artifacts/benchmarks/run_device_benchmark.sh 3
```

---

## 6. Repository map

```
pixel-android-app/        Native Android app (Kotlin + Jetpack Compose); HOT path,
                          LiteRT-LM edge model, AICore reasoner, benchmark harness
koru-application/         Web app (React/Vite) — same Split-Brain engine in TS
streaming-telemetry-server/  Python telemetry ingest (NMEA parser unit-tested)
sonoma-training-e2e/      On-device E2E sim-runner + offline validator + recorded runs
packages/core-telemetry/  Shared telemetry types
shared/coaching-phrases.json   Persona/skill phrase catalog (the coaching paradigm)
submission-artifacts/     Benchmarks (renderer, runner, reports, chart) + media
docs/                     Architecture, audit, and the blog series
```

---

## 7. Artifacts index

- **Audit & honest status:** [`docs/audit-2026-06-08.md`](docs/audit-2026-06-08.md)
- **Blog series (prologue + 5 parts, Blogger-ready HTML):** [`docs/blog/`](docs/blog/)
  0. [Prologue — It Started With a Wine List (the origin story)](docs/blog/00-the-origin-story.html)
  1. [The 800-Millisecond Problem](docs/blog/01-the-800ms-problem.html)
  2. [Teaching the Coach to Read the Driver](docs/blog/02-reading-the-driver.html)
  3. [Splitting the Brain to Beat the Clock](docs/blog/03-splitting-the-brain.html)
  4. [Putting Gemma in the Cockpit](docs/blog/04-gemma-in-the-cockpit.html)
  5. [Earning Trust at Speed](docs/blog/05-earning-trust-at-speed.html)
- **Benchmark suite:** [`submission-artifacts/benchmarks/`](submission-artifacts/benchmarks/)
- **Submission index & media:** [`submission-artifacts/README.md`](submission-artifacts/README.md)
- **Push instructions:** [`PUBLISH.md`](PUBLISH.md)

_All performance figures are from the 2026-06-08 Pixel 10 runs recorded in the
artifacts above. No number in this document is estimated._
