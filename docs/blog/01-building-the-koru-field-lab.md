# Building The Koru Field Lab

Koru started as a racing coach that had to earn trust at speed. The app watches telemetry, camera context, and session goals, then chooses when to speak and when to stay quiet. The important design constraint is simple: safety cues must never wait for a cloud model or an on-device LLM.

![Koru setup screen](../../pixel-android-app/screenshots/setup-screen.png)

The current Android cockpit opens on setup because track work starts before the car rolls. The driver chooses the mode, telemetry source, track, coach voice, and HUD behavior in one place. The UI is intentionally operational: high contrast, compact controls, and direct status language.

## Why Native Android

The native Pixel app replaced the earlier WebView field-test path so the runtime could own camera, foreground services, local audio, telemetry fusion, and model status directly. Jetpack Compose gives the app a predictable field surface, while Kotlin keeps the hot path close to the Android sensors and services.

The runtime has three live lanes:

- `Telemetry + Camera Fusion` for the intended track-day flow.
- `Device Camera + GPS Test` for phone-only validation.
- `Camera Feedback` for debugging the camera path without vehicle hardware.

Each lane feeds the same session bus, so UI state, decisions, saved sessions, and diagnostics can be observed consistently.

## What The Driver Sees

The first screen is not a marketing page. It is a pre-drive checklist. The app shows whether the backend is ready, which telemetry source is selected, which learning goals are active, and whether audio cues will fire in Track Mode.

That matters because a field tool should not hide uncertainty. If the model is unavailable, Koru still shows the deterministic fallback. If the camera is waiting, it says so. If hardware is not connected, the diagnostics tab gives the next actionable state instead of pretending everything is fine.

## Submission Assets

Use these project artifacts with this post:

- `pixel-android-app/screenshots/setup-screen.png`
- `submission-artifacts/media/pixel-setup-screen.png`
- `submission-artifacts/media/koru-demo-interaction.mp4`
