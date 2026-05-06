# Pixel 10 E2E Testing

This Android app now supports three native live-session lanes:

- `Telemetry + Camera Fusion`
- `Device Camera + GPS Test`
- `Camera Feedback (Debug)`

The current deployment target in this branch is Sonoma Raceway. The native track catalog and coaching runtime now include Sonoma-specific corner guidance derived from the sector map plus coaching notes.

The Android field-test surface is now Jetpack Compose + Material 3. The first two modes run through the foreground-service telemetry pathway. The debug lane uses the activity-local camera-direct pathway.

## Model choice

For the native Android backend in this repo, use a native LiteRT-LM artifact such as:

- `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`

Do not use:

- `gemma-4-E2B-it-web.task`

That file is the web/WebGPU artifact for browser inference, not the native Android backend in this project.

## Prepare the model

The Compose live UI no longer requires the React bundle for field testing. To stage the native LiteRT-LM model from [koru-application](/Users/rkaranjai/Documents/trustable-ai-codelab/koru-application), run:

```bash
npm install
npm run pixel:model:stage
npm run pixel:model:push
```

The older `npm run pixel:e2e:prepare` command still builds and syncs the React assets for compatibility, but the live Android path does not load them.

Optional overrides:

```bash
KORU_PIXEL_MODEL_URL=... \
KORU_PIXEL_MODEL_VERSION=... \
KORU_PIXEL_MODEL_FILENAME=... \
KORU_PIXEL_MODEL_SHA256=... \
npm run pixel:e2e:prepare
```

## Run on device

1. Open [pixel-android-app](/Users/rkaranjai/Documents/trustable-ai-codelab/pixel-android-app) in Android Studio.
2. Connect the Pixel 10 with USB debugging enabled.
3. Run the `app` module to the device.
4. Open `Live Session`.
5. Choose one of:
   - `Telemetry + Camera Fusion`
   - `Device Camera + GPS Test`
   - `Camera Feedback (Debug)`
6. Grant camera and/or location permissions when prompted.
7. Start the selected session.

## What end-to-end means in the current branch

The complete flow you can test now is:

1. the Compose UI renders the live cockpit and session initialization flow
2. `LiveSessionViewModel` starts the selected Android live session mode
3. `KoruSessionBus` exposes direct `StateFlow` state for UI status, telemetry, decisions, and saved sessions
4. the native runtime selects a telemetry path:
   - `phone_imu_gps`
   - `synthetic`
   - future `racebox_ble` / `obd_bluetooth`
5. CameraX captures live camera frames and extracts lightweight vision features
6. telemetry frames are fused with the latest vision snapshot when the session mode uses telemetry
7. Sonoma-specific corner/feedforward guidance is applied when the selected track is Sonoma Raceway
8. the on-device runtime chooses AICore when available, otherwise MediaPipe LiteRT GPU EDGE inference when a staged model is ready, otherwise deterministic fallback
9. native audio dispatch plays bundled spoken P0 safety clips or immediately falls back to flushed Android TTS
10. the recorded session artifact is saved for replay and analysis with schema v2 audio latency evidence

## Current limitations

- `phone_imu_gps` is the first real native telemetry source, but it still infers driving intent from phone sensors and GPS.
- RaceBox BLE and OBD ingestion are still stubbed.
- `Camera Feedback (Debug)` is useful for validating the camera lane, but it is not race-grade coaching by itself.
- Vision features are still low-level and do not yet encode track edges, apexes, or brake markers.
- AICore is still scaffolded.
- LiteRT-LM model push is automated, but actual GPU EDGE validation still depends on the device having a compatible native inference runtime, a staged model, and a successful app build from Android Studio. HOT/P0 does not depend on this path.
- The React replay and analysis workbench is intentionally still separate from the native field-test cockpit.

## Recommended test flows

### 1. Device-only fused testing

Use `Device Camera + GPS Test` when you want to validate end-to-end realtime behavior on the phone without a baked track path.

### 2. Main fused Android lane

Use `Telemetry + Camera Fusion` with `Phone IMU + GPS` as the current real on-device telemetry source. This is the main Sonoma-specific fused coaching path in the current branch.

### 3. Camera lane debugging

Use `Camera Feedback (Debug)` to validate camera ingestion, vision features, and on-device audio independently of telemetry.

## Pixel 10 validation commands

```bash
cd /Users/rkaranjai/Documents/trustable-ai-codelab/pixel-android-app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest assembleDebug connectedDebugAndroidTest
adb logcat -c
adb logcat -v time | rg "KoruTelemetryService|KoruAudioDispatcher|AndroidRuntime|FATAL|ANR|FGS"
```

During a running `Telemetry + Camera Fusion` session, `dumpsys` should show the foreground service:

```bash
adb shell dumpsys activity services com.trustableai.koru.debug | rg "KoruTelemetryService|foreground|startRequested"
```
