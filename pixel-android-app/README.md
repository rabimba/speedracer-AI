# Pixel 10 E2E Testing

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
4. Open `Live Session` and press `Connect`.

## What end-to-end means in the current branch

The complete flow you can test now is:

1. native foreground service starts
2. synthetic telemetry frames are generated
3. deterministic engine produces hot/feedforward decisions
4. LiteRT-LM model asset is discovered on-device
5. backend selection chooses `litertlm` when the runtime is available
6. native bridge sends frames and decisions into the WebView UI
7. native TTS plays coaching audio

## Current limitations

- RaceBox BLE and OBD ingestion are still stubbed.
- AICore is still scaffolded.
- LiteRT-LM model push is automated, but actual runtime validation still depends on the device having a compatible native inference runtime and a successful app build from Android Studio.
