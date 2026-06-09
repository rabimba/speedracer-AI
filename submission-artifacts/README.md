# Racecraft — Submission Artifacts

Submission index for the current **Racecraft** snapshot (formerly "Koru"; internal
module ids keep the `koru` codename). Repo: `https://github.com/rabimba/speedracer-AI`.

## Start here

- **Audit & honest status (2026-06-08):** `docs/audit-2026-06-08.md`
- **Blog series (5 parts, Blogger-ready HTML):** `docs/blog/` — see `docs/blog/README.md`
- **Benchmarks (GPU vs CPU vs NPU):** `submission-artifacts/benchmarks/` — see its `README.md`
- **Publish/push instructions:** `PUBLISH.md`

## Build Artifacts

- Android debug APK: `pixel-android-app/app/build/outputs/apk/debug/app-debug.apk`
- APK is ~114 MB → upload as a release/external asset, **not** committed to GitHub.

## Validation Artifacts

- Full repo audit (new): `docs/audit-2026-06-08.md`
- Prior architecture review: `AUDIT_REPORT.md`
- Current implementation audit: `docs/current-implementation-audit.md`
- Hardware validation notes: `docs/hardware-validation.md`
- **On-device E2E run (validated 16/16):** `sonoma-training-e2e/reports/20260503-220239/`
  - Re-validate: `node sonoma-training-e2e/tools/validate-report.mjs --artifact <dir>/recorded-session.json --logcat <dir>/logcat.txt --instrumentation <dir>/instrumentation.txt --metadata <dir>/metadata.json --out /tmp/val`
- Telemetry parser test (passes 4/4): `streaming-telemetry-server/test_nmea_parsing.py`

## Benchmarks (GPU / CPU / NPU)

- Current honest report: `submission-artifacts/benchmarks/accelerator-comparison-report.json`
- Rendered table + chart: `accelerator-comparison-report.md`, `accelerator-comparison-report.svg`
- Renderer (any report → md+svg): `render_benchmark_report.py`
- Real device runner: `run_device_benchmark.sh`
- Host-CPU methodology proxy (synthetic, labeled): `host_cpu_methodology_proxy.py`

## Blog And Media

- Blog series (HTML, paste into Blogger): `docs/blog/01..05-*.html`
- Inline blog figures (also standalone): `docs/blog/assets/*.svg`
- Checked-in Android screenshots: `pixel-android-app/screenshots/{setup,diagnostics}-screen.png`
- Refreshed unlocked-device captures via `npm run artifacts:pixel:capture`:
  - `submission-artifacts/media/pixel-{setup,diagnostics,paddock}-screen.png`
  - `submission-artifacts/media/koru-demo-interaction.mp4`

## Known Submission Notes (honest)

- The deterministic HOT/P0 path is validated on a real Pixel 10: **HOT p95 = 5.00 ms**
  (budget 50 ms), **P0 audio dispatch max = 5.00 ms** (budget 100 ms), 16/16 checks.
- On-device token generation is **measured** (2026-06-08, Pixel 10, LiteRT-LM):
  **NPU 14.3 tok/s @ 424 ms TTFT** (fastest), GPU 10.3 @ 1108 ms, CPU 6.9 @ 8545 ms.
  Unblocked by a text-only Gemma 4 E2B re-export + a Tensor-G5 NPU artifact and the
  packaged `libLiteRtDispatch_GoogleTensor.so` dispatch runtime.
- The phone must be unlocked before UI screenshots, video, or Compose connected tests.
