# Pixel 10 E2E Testing

This Android app now supports three native live-session lanes:

- `Telemetry + Camera Fusion`
- `Device Camera + GPS Test`
- `Camera Feedback (Debug)`

The current deployment target in this branch is Sonoma Raceway. The native track catalog and coaching runtime now include Sonoma-specific corner guidance derived from the sector map plus coaching notes.

The first two run through the foreground-service telemetry pathway. The debug lane uses the activity-local camera-direct pathway.

## Model choice

For the native Android backend in this repo, use a native LiteRT-LM artifact such as:

- `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`

Do not use:

- `gemma-4-E2B-it-web.task`

That file is the web/WebGPU artifact for browser inference, not the native Android backend in this project.

## Prepare the web bundle and model

From [koru-application](/Users/rkaranjai/Documents/trustable-ai-codelab/koru-application):

```bash
npm install
npm run pixel:e2e:prepare
```

That does three things:

1. builds the React UI and copies it into Android assets
2. stages the native LiteRT-LM model under `pixel-android-app/models/`
3. pushes the model to `/data/local/tmp/koru/models/<version>/` on the attached device

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

1. the React UI is loaded inside the Android WebView host
2. the native bridge starts the selected Android live session mode
3. the native runtime selects a telemetry path:
   - `phone_imu_gps`
   - `synthetic`
   - future `racebox_ble` / `obd_bluetooth`
4. CameraX captures live camera frames and extracts lightweight vision features
5. telemetry frames are fused with the latest vision snapshot when the session mode uses telemetry
6. Sonoma-specific corner/feedforward guidance is applied when the selected track is Sonoma Raceway
7. the on-device runtime chooses `litertlm` when available, otherwise deterministic fallback
8. native bridge sends frames and decisions into the WebView UI
9. native TTS plays coaching audio
10. the recorded session artifact is saved for replay and analysis

## Current limitations

- `phone_imu_gps` is the first real native telemetry source, but it still infers driving intent from phone sensors and GPS.
- RaceBox BLE and OBD ingestion are still stubbed.
- `Camera Feedback (Debug)` is useful for validating the camera lane, but it is not race-grade coaching by itself.
- Vision features are still low-level and do not yet encode track edges, apexes, or brake markers.
- AICore is still scaffolded.
- LiteRT-LM model push is automated, but actual runtime validation still depends on the device having a compatible native inference runtime and a successful app build from Android Studio.

## Recommended test flows

### 1. Device-only fused testing

Use `Device Camera + GPS Test` when you want to validate end-to-end realtime behavior on the phone without a baked track path.

### 2. Main fused Android lane

Use `Telemetry + Camera Fusion` with `Phone IMU + GPS` as the current real on-device telemetry source. This is the main Sonoma-specific fused coaching path in the current branch.

### 3. Camera lane debugging

Use `Camera Feedback (Debug)` to validate camera ingestion, vision features, and on-device audio independently of telemetry.
