# Koru Submission Artifacts

This folder is the submission index for the current Koru project snapshot.

## Build Artifacts

- Android debug APK: `pixel-android-app/app/build/outputs/apk/debug/app-debug.apk`
- Generated APK size on the current machine: about 114 MB, so it should be uploaded as a release or external submission asset instead of committed to GitHub.
- Source repository: `https://github.com/rkaranjai_paypal/trustable-ai-codelab`

## Validation Artifacts

- Full repo audit: `AUDIT_REPORT.md`
- Current implementation audit: `docs/current-implementation-audit.md`
- Hardware validation notes: `docs/hardware-validation.md`
- Pixel accelerator comparison report: `submission-artifacts/benchmarks/accelerator-comparison-report.json`
- Pixel bugreport from the current connected-device run: `/tmp/koru-bugreport.zip`

## Blog And Media

- Blog posts: `docs/blog/`
- Checked-in Android screenshots:
  - `pixel-android-app/screenshots/setup-screen.png`
  - `pixel-android-app/screenshots/diagnostics-screen.png`
- Refreshed unlocked-device captures from `npm run artifacts:pixel:capture`:
  - `submission-artifacts/media/pixel-setup-screen.png`
  - `submission-artifacts/media/pixel-diagnostics-screen.png`
  - `submission-artifacts/media/pixel-paddock-screen.png`
  - `submission-artifacts/media/koru-demo-interaction.mp4`

## Known Submission Notes

- The deterministic HOT/P0 path builds and passes unit tests.
- Pixel 10 is connected and the staged model checksum matches the expected SHA256.
- The current Gemma 4 E2B LiteRT-LM artifact is blocked for native MediaPipe token generation because the runtime rejects `tf_lite_audio_adapter`.
- AICore/NPU is currently scaffolded and status-reported; it is not yet a measured token-generation lane.
- The phone must be unlocked before UI interaction screenshots, videos, or Compose connected UI tests can run.
