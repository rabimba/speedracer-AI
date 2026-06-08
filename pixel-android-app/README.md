# Pixel 10 E2E Testing

This Android app now supports three native live-session lanes:

- `Telemetry + Camera Fusion`
- `Device Camera + GPS Test`
- `Camera Feedback (Debug)`

The current deployment target in this branch is Sonoma Raceway. The native track catalog and coaching runtime now include Sonoma-specific corner guidance derived from the sector map plus coaching notes.

The Android field-test surface is now Jetpack Compose + Material 3. The first two modes run through the foreground-service telemetry pathway. The debug lane uses the activity-local camera-direct pathway.

The live app does not host a WebView. `MainActivity` renders `KoruApp` with Jetpack Compose, and on-device EDGE inference runs through native Kotlin (`EdgeRuntimeManager` -> `LiteRtLmReasoner` -> MediaPipe `LlmInference`). Legacy React assets under `app/src/main/assets/web/` may still be synced by `npm run pixel:e2e:prepare`, but the live Android path does not load them.

## Model choice

For the native Android backend in this repo, use a native LiteRT-LM artifact such as:

- `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`

Current Pixel 10 validation note, 2026-06-08: the staged `gemma-4-E2B-it.litertlm` file checksum-verifies, but the bundled MediaPipe `tasks-genai:0.10.27` runtime rejects its `tf_lite_audio_adapter` model type during native startup. Keep the model guard enabled and treat that artifact as not validated for native Android token generation until a compatible text-only LiteRT-LM artifact or runtime update is used.

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
6. Grant camera, Bluetooth, USB, and/or location permissions when prompted.
7. Start the selected session.

## What end-to-end means in the current branch

The complete flow you can test now is:

1. the Compose UI renders the live cockpit and session initialization flow
2. `LiveSessionViewModel` starts the selected Android live session mode
3. `KoruSessionBus` exposes direct `StateFlow` state for UI status, telemetry, decisions, and saved sessions
4. the native runtime selects a telemetry path:
   - `phone_imu_gps`
   - `synthetic`
   - `racebox_ble`
   - `obd_bluetooth`
   - `racebox_obd_fusion`
5. CameraX captures live camera frames and extracts lightweight vision features
6. telemetry frames are fused with the latest vision snapshot when the session mode uses telemetry
7. Sonoma-specific corner/feedforward guidance is applied when the selected track is Sonoma Raceway
8. the on-device runtime chooses AICore when available, otherwise MediaPipe LiteRT GPU EDGE inference when a staged model is ready, otherwise deterministic fallback
9. native audio dispatch plays bundled spoken P0 safety clips or immediately falls back to flushed Android TTS
10. the recorded session artifact is saved for replay and analysis with schema v2 audio latency evidence

## Current limitations

- `phone_imu_gps` is the first real native telemetry source, but it still infers driving intent from phone sensors and GPS.
- RaceBox BLE, OBDLink Bluetooth Classic, and the fused RaceBox + OBDLink source are implemented in software but still require physical GR86 validation before they should be trusted for track coaching.
- Steering angle and true brake pressure are still unavailable until GR86 enhanced PIDs or a direct CAN path are verified.
- `Camera Feedback (Debug)` is useful for validating the camera lane, but it is not race-grade coaching by itself.
- Vision features are still low-level and do not yet encode track edges, apexes, or brake markers.
- AICore is still scaffolded.
- LiteRT-LM model push is automated, but actual GPU EDGE validation still depends on the device having a compatible native inference runtime, a staged model, and a successful app build from Android Studio. HOT/P0 does not depend on this path.
- The React replay and analysis workbench is intentionally still separate from the native field-test cockpit.

## Recommended test flows

### 1. Device-only fused testing

Use `Device Camera + GPS Test` when you want to validate end-to-end realtime behavior on the phone without a baked track path.

### 2. Main fused Android lane

Use `Telemetry + Camera Fusion` with `RaceBox + OBDLink` for the intended GR86 field-test path. RaceBox Mini supplies GPS, speed, heading, and G forces at the live frame cadence; OBDLink enriches frames with RPM, throttle, coolant temperature, oil temperature, and post-run diagnostics when the standard PIDs respond.

The OBD selector supports:

- `Auto`: use OBDLink EX over USB OTG when attached and permitted, otherwise fall back to paired OBDLink MX+ Bluetooth.
- `Bluetooth MX+`: force Bluetooth Classic RFCOMM to an already paired OBDLink MX+.
- `USB EX`: force USB OTG serial for OBDLink EX.

The app only sends read-only ELM/STN setup and Mode 01 PID requests. It does not clear DTCs or write vehicle configuration.

Use `Phone IMU + GPS` as the no-hardware fallback when validating the Android runtime away from the car.

### 3. Camera lane debugging

Use `Camera Feedback (Debug)` to validate camera ingestion, vision features, and on-device audio independently of telemetry.

## Pixel 10 validation commands

```bash
cd /Users/rkaranjai/Documents/trustable-ai-codelab/pixel-android-app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest assembleDebug connectedDebugAndroidTest
adb logcat -c
adb logcat -v time | rg "KoruTelemetryService|KoruAudioDispatcher|AndroidRuntime|FATAL|ANR|FGS"
```

To write a CPU/GPU/NPU accelerator comparison artifact without bypassing the model guard:

```bash
cd /Users/rkaranjai/Documents/trustable-ai-codelab/pixel-android-app
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.trustableai.koru.runtime.benchmark.AcceleratorComparisonInstrumentedTest#compareCpuGpuNpuTokenGenerationSpeed \
  -Pandroid.testInstrumentationRunnerArguments.runsPerStrategy=1
adb pull /sdcard/Download/koru-accelerator-comparison-report.json \
  /Users/rkaranjai/Documents/trustable-ai-codelab/submission-artifacts/benchmarks/accelerator-comparison-report.json
```

To capture submission screenshots and a short interaction video from an unlocked Pixel:

```bash
cd /Users/rkaranjai/Documents/trustable-ai-codelab
npm run artifacts:pixel:capture
```

During a running `Telemetry + Camera Fusion` session, `dumpsys` should show the foreground service:

```bash
adb shell dumpsys activity services com.trustableai.koru.debug | rg "KoruTelemetryService|foreground|startRequested"
```
