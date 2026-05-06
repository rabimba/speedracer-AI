# Native Compose Field-Test Migration

This branch moves the Android live field-test path off the WebView shell and onto a native Jetpack Compose + Material 3 cockpit. The browser app remains the replay, analysis, and web-demo workbench.

## Runtime Shape

- `MainActivity` owns Android permissions and CameraX binding.
- `KoruApp` renders the live cockpit, session initialization, camera lane, coach panel, and saved-session status.
- `LiveSessionViewModel` starts/stops `KoruTelemetryService` or `CameraDirectSessionController` and mirrors user choices into native runtime commands.
- `KoruSessionBus` now exposes `StateFlow` surfaces for latest telemetry, bounded decision history, backend status, and latest saved session.
- The live loop no longer serializes telemetry or coaching decisions through a JavaScript bridge.

## Safety Changes

- FEEDFORWARD anticipation uses speed-scaled lookahead: `speed_mps * 4s`, clamped to `120m..320m`.
- Sonoma Turns 9-10 and 11 use a longer 5-second lead target for high-speed braking preparation.
- Feedforward only fires when GPS progress or heading indicates the car is approaching the corner.
- P0 field-test actions are `BRAKE` and `OVERSTEER_RECOVERY`.
- P0 text bypasses persona humanization: `BRAKE -> Brake now`, `OVERSTEER_RECOVERY -> Both feet in`.
- EDGE reasoner calls are isolated in a single-flight async queue. The live frame loop never waits for GPU/LLM work; completed EDGE results must return within 750ms and are dropped after a 1500ms stale TTL.
- Pixel 10 GPU inference is scoped to non-P0 EDGE enrichment through MediaPipe LiteRT when a staged native model is available. HOT/P0 safety remains deterministic and independent of model loading, GPU scheduling, or JSON parsing.
- Native P0 audio uses bundled spoken WAV clips for `Brake now` and `Both feet in`, then falls back to `TextToSpeech.QUEUE_FLUSH` if a clip is missing or not loaded. Tone-only P0 dispatch is no longer allowed.
- Recorded session schema v2 persists `audioEvents` linked to coaching decision IDs, including dispatch latency, optional TTS start latency, status, and fallback reason.

## Validation

Run Android checks with Android Studio's bundled JBR if no system JDK is installed:

```bash
cd pixel-android-app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug connectedDebugAndroidTest
```

The unit suite covers dynamic feedforward timing, approach gating, queue preemption, P0 bypass while EDGE hangs, P0 audio clip/TTS fallback events, EDGE timeout/stale-result behavior, and recorded-session audio evidence.

Manual Pixel 10 validation should include:

```bash
adb logcat -c
adb logcat -v time | rg "KoruTelemetryService|KoruAudioDispatcher|AndroidRuntime|FATAL|ANR|FGS"
adb shell dumpsys activity services com.trustableai.koru.debug | rg "KoruTelemetryService|foreground|startRequested"
```

Expected runtime evidence:

- `Telemetry loop started source=phone_imu_gps cameraFusion=true`
- `KoruTelemetryService` remains active while the live session is running
- P0 audio logs include `P0 clip dispatched` or `TTS queued` / `TTS started` fallback metrics
- no `FATAL`, `ANR`, or unexpected foreground-service stop during an active session
