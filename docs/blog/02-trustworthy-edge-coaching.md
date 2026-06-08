# Trustworthy Edge Coaching

The trust problem in a racing coach is not whether an LLM can produce a helpful sentence. The problem is whether the system can decide, under latency pressure, which path is allowed to influence the driver.

Koru separates coaching into deterministic and model-assisted paths. Priority zero safety cues are produced by deterministic logic and fixed audio clips. The model-assisted EDGE path can enrich lower-priority coaching, but it is never allowed to block or replace the safety lane.

![Koru diagnostics screen](../../pixel-android-app/screenshots/diagnostics-screen.png)

## The Runtime Contract

The Android runtime chooses the best available backend in this order:

- AICore status path when the runtime is present.
- MediaPipe LiteRT GPU inference when a compatible native model is staged.
- Deterministic fallback when model execution is unavailable.

The fallback is not a failure mode. It is the safety baseline. HOT/P0 cues, feedforward timing, and audio dispatch keep working without GPU, NPU, network, or JSON parsing.

## Why Model Guards Matter

The current Pixel 10 validation found a useful failure. The staged Gemma 4 E2B LiteRT-LM model checksum matched on host and device, but MediaPipe rejected the artifact at native startup because it contains an unsupported `tf_lite_audio_adapter` model type.

Koru's model guard catches that before the app enters the native inference path. A diagnostic bypass proved the guard is necessary: MediaPipe aborted with `INVALID_ARGUMENT: Unknown model type: tf_lite_audio_adapter`.

That is the kind of failure a trustworthy edge app should make visible. A blocked model is better than a crashing runtime.

## What We Can Claim

The validated claim is that Koru's deterministic field path works and the app degrades safely when the native model path is incompatible. The unvalidated claim is native LiteRT token generation for this specific model/runtime pair. That stays blocked until the model or runtime changes.

Use these project artifacts with this post:

- `docs/hardware-validation.md`
- `submission-artifacts/benchmarks/accelerator-comparison-report.json`
- `pixel-android-app/screenshots/diagnostics-screen.png`
