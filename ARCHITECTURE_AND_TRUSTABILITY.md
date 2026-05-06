# System Architecture & Trustability Report: Trustable AI Race Coach

This document details the architectural patterns, performance optimizations, and validation strategies that enable the Trustable AI Race Coach to deliver adaptive, real-time coaching while ensuring reliability and user trust.

---

## 1. Real-Time Adaptability to User Skill

The system's ability to tailor coaching to the individual is centered around the **`DriverModel` service**. This module creates a dynamic profile of the driver's skill level, which in turn modulates the behavior of the entire coaching engine.

### 1.1. Real-Time Skill Assessment

- **Mechanism**: The `DriverModel` continuously analyzes telemetry on a **10-second rolling window** to classify skill level. It uses a 5-second hysteresis to prevent rapid, distracting changes.
- **Core Metrics**:
    1.  **Input Smoothness**: Measures the consistency of brake, throttle, and steering inputs. Erratic, jagged inputs indicate a lower skill level.
    2.  **Coasting Ratio**: Calculates the percentage of time spent with neither brake nor throttle applied. Beginners tend to coast more out of uncertainty.
- **Classification**: Based on these metrics, the driver is classified in real-time as `BEGINNER`, `INTERMEDIATE`, or `ADVANCED`.

### 1.2. Dynamic Adaptation Mechanisms

The skill classification directly influences four key aspects of the coaching output:

| Adaptation            | For a `BEGINNER`                                                                    | For an `ADVANCED`                                                                   |
| :-------------------- | :---------------------------------------------------------------------------------- | :---------------------------------------------------------------------------------- |
| **Coaching Language** | Feel-based, encouraging, and avoids jargon. (e.g., "Squeeze the brakes, don't stab.") | Technical and data-driven. (e.g., "Brake spike detected. Modulate your input.")      |
| **Coaching Frequency**  | Longer cooldowns between messages (3000ms) and "blackout" periods during complex maneuvers (e.g., apex). | Much shorter cooldowns (650ms) and no blackout periods, allowing for granular feedback. |
| **Session Progression** | Suppresses advanced techniques (e.g., trail braking) during the initial phases of a session. | Has immediate access to the full range of coaching actions.                          |
| **AI Prompting**        | Instructs the Gemini model to provide simple, feel-based sentences.                 | Instructs the Gemini model to reference specific telemetry numbers in its analysis.   |

---

## 2. Real-Time Coaching Architecture

The system's real-time capability is achieved through a combination of the "Split-Brain" architectural pattern, intelligent queuing, and edge computing. The entire loop is designed to meet a strict latency budget of **300-500ms**.

### 2.1. The "Split-Brain" Coaching Engine

This is the core architectural pattern, routing decisions through three asynchronous paths based on urgency:

- **HOT Path (<50ms)**: For instantaneous, critical feedback.
    - **Mechanism**: A deterministic `DECISION_MATRIX` of hard-coded heuristic rules that are computationally cheap to evaluate on every telemetry frame.
    - **Purpose**: Handles P0 (Priority 0) safety events like **"Brake!"** or **"Oversteer Recovery!"** and fundamental P1 tactical advice. Its speed and reliability are guaranteed as it involves no network calls or complex AI inference.

- **COLD Path (2-5 seconds)**: For strategic, "big picture" analysis.
    - **Mechanism**: Gathers deep context and makes an `async` call to the Gemini Cloud AI.
    - **Purpose**: Provides insights that are not time-sensitive, such as, "You're consistently lifting early in Turn 5 — trust the grip through mid-corner." The system **does not wait** for this path to return.

- **FEEDFORWARD Path (Geofenced)**: For proactive, corner-specific advice.
    - **Mechanism**: Uses GPS geofencing to trigger pre-scripted advice ~150m before a known corner.
    - **Purpose**: Prepares the driver for the upcoming challenge (e.g., "T3 right: late apex, brake at the 100m board.").

### 2.2. Intelligent Queuing and Filtering

- **`CoachingQueue`**: A priority queue that acts as a traffic controller for messages. A P0 safety message will always **preempt** and be delivered before any lower-priority tactical or strategic message. This ensures that the most critical advice is never delayed.
- **`TimingGate`**: A state machine that ensures advice is relevant. By tracking the car's `CornerPhase` (e.g., 'BRAKING', 'APEX'), it can enforce silence during moments of high cognitive load, preventing the system from delivering ill-timed, distracting advice.

---

## 3. AI Models & Performance Optimizations

### 3.1. Hybrid Model Strategy

The system uses a carefully selected hierarchy of models, using the right tool for the right job.

| Path      | Model / Engine                 | Role                     | Execution Location    |
| :-------- | :----------------------------- | :----------------------- | :-------------------- |
| **COLD**  | **Gemini 2.5 Flash Lite**      | Strategic Analysis       | **Cloud** (REST API)  |
| **EDGE**  | **LiteRT-LM / MediaPipe**      | Tactical Enrichment      | **Edge** (Android GPU)|
| **HOT**   | **Deterministic Heuristics**   | Instantaneous Safety     | **Edge** (CPU)        |

Critically, **the core real-time feedback loop does not depend on a large language model.** It is driven by the fast and predictable deterministic `DECISION_MATRIX`.

### 3.2. Performance Optimizations

- **Architectural**: The "Split-Brain" design is the primary optimization, ensuring the HOT path is never blocked.
- **Algorithmic**: The HOT path uses a simple array of rules, making evaluation computationally trivial and extremely fast.
- **Non-Blocking Execution**: All I/O, including calls to the `COLD` path and on-device `EDGE` models, is asynchronous. The on-device model runs in a "single-flight async queue," preventing request backlogs and ensuring it never blocks the critical path.
- **Hardware/OS Level**: The Android app explicitly requests `CONNECTION_PRIORITY_HIGH` for the RaceBox BLE connection, telling the OS to minimize latency and jitter for the most critical sensor data stream.

---

## 4. Validation & Trustability Strategy

Trust is the central design goal, earned through a rigorous, multi-layered validation strategy built on the principle that "Feedback 800ms late is worse than silence."

### 4.1. Determinism and Auditability

The foundation of trust is the **deterministic HOT path**. Because it is a set of human-written, auditable rules, its behavior is predictable and verifiable. A senior engineer can read the code and know exactly why a critical safety message was triggered.

### 4.2. Comprehensive, Multi-Layered Testing

- **Unit Tests (`vitest`)**: Core logic modules like `DriverModel`, `TimingGate`, and `CornerPhaseDetector` are individually validated.
- **Integration Tests**: A "Sonoma CSV integration test" validates the entire coaching service as a single unit against a known-good telemetry file, ensuring the sequence and content of coaching are correct.
- **End-to-End Validation (`sonoma-training-e2e`)**: A dedicated test suite runs the full system against realistic scenarios, generating reports to validate the accuracy and performance of the AI across entire simulated laps.

### 4.3. In-Field Validation and Transparency

The Android app includes specific debug modes (`Device Camera + GPS Test`, `Camera Feedback (Debug)`) for on-track validation of individual system components.

Furthermore, the system is designed for complete post-session auditability. Native sessions persist the entire **"fused frame timeline and decisions,"** including **"audio dispatch latency evidence."** This allows a developer or data scientist to replay any session, analyze the AI's performance, and verify that its advice was both correct and timely, creating a powerful feedback loop for continuous improvement.
