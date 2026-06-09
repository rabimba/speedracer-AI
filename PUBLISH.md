# Publishing Racecraft → github.com/rabimba/speedracer-AI

The build sandbox has **no GitHub credentials and no network to GitHub**, so the push
is done from your machine. Everything is staged and clean; this is copy-paste.

The remote is already wired:

```bash
$ git remote -v
origin  https://github.com/rabimba/speedracer-AI.git (fetch)
origin  https://github.com/rabimba/speedracer-AI.git (push)
```

## 0. Create the GitHub repo (only if it doesn't exist yet)

```bash
# with GitHub CLI:
gh repo create rabimba/speedracer-AI --public --source . --remote origin --push
# …or create it empty in the GitHub web UI, then continue below.
```

## 1. Sanity-check what will be committed

`.gitignore` already excludes `node_modules/`, `venv/`, all `build/` + `.gradle/`,
`*.apk`, `*.litertlm` models, and stray validator outputs. Confirm nothing huge slips in:

```bash
git status
git ls-files --others --exclude-standard | xargs -I{} du -m "{}" 2>/dev/null | sort -rn | head
```

## 2. Commit (two clean, logical commits)

> If you see `Unable to create '.git/index.lock'`, an editor/IDE git process is
> holding it — close it, or `rm -f .git/index.lock`, then continue.

```bash
# (identity is already set in this repo's history; skip if yours is configured)
# git config user.name "Rabimba Karanjai"; git config user.email "you@example.com"

# --- Commit 1: on-device GPU/CPU/NPU benchmark enablement ---
git add \
  .gitignore \
  pixel-android-app/app/build.gradle.kts \
  pixel-android-app/app/src/main/AndroidManifest.xml \
  pixel-android-app/app/src/androidTest/java/com/trustableai/koru/runtime/benchmark/AcceleratorComparisonInstrumentedTest.kt \
  pixel-android-app/app/src/main/java/com/trustableai/koru/runtime/ModelAssetManager.kt \
  pixel-android-app/app/src/main/java/com/trustableai/koru/runtime/benchmark/OfficialLiteRtLmBenchmarkRunner.kt \
  pixel-android-app/app/src/main/jniLibs \
  scripts/fetch-pixel-model.mjs scripts/push-pixel-model.mjs \
  submission-artifacts/benchmarks/run_device_benchmark.sh \
  submission-artifacts/benchmarks/merge_accelerator_reports.py \
  submission-artifacts/benchmarks/render_benchmark_report.py \
  submission-artifacts/benchmarks/host_cpu_methodology_proxy.py \
  submission-artifacts/benchmarks/README.md \
  submission-artifacts/benchmarks/accelerator-comparison-report.json \
  submission-artifacts/benchmarks/accelerator-comparison-report.md \
  submission-artifacts/benchmarks/accelerator-comparison-report.svg \
  submission-artifacts/benchmarks/host-cpu-proxy-report.md \
  submission-artifacts/benchmarks/host-cpu-proxy-report.svg
git commit -m "Enable on-device GPU/CPU/NPU LiteRT-LM benchmark + reporting tools" \
  -m "Per-lane process isolation; Tensor G5 NPU dispatch (libLiteRtDispatch_GoogleTensor.so); model staging. Pixel 10: NPU 14.3 tok/s @424ms TTFT, GPU 10.3 @1108ms, CPU 6.9 @8545ms."

# --- Commit 2: submission docs, blog series, web UI polish, parser fix ---
git add -A
git commit -m "Racecraft: submission writeup, audit, 5-part blog series, UI polish, parser decoupling"
```

## 3. Push

```bash
# publish the current work as main (recommended for a fresh public repo):
git branch -M main
git push -u origin main

# …or keep the existing branch name:
# git push -u origin ag-aj-car
```

## 4. The APK (too big for git)

The debug APK is ~114 MB and is gitignored on purpose. Attach it to a GitHub Release:

```bash
gh release create v0.1.0 \
  pixel-android-app/app/build/outputs/apk/debug/app-debug.apk \
  --title "Racecraft v0.1.0" --notes "On-device race coach — debug build"
```

---

## Get the real device artifacts (your Pixel is connected)

These need the phone connected + **unlocked**, run from your machine (the sandbox
can't see the device). Both already exist as scripts in the repo.

```bash
# Fresh screenshots + the interaction recording → submission-artifacts/media/
npm run artifacts:pixel:capture

# Real GPU vs CPU vs NPU numbers (needs a native-ready text-only Gemma artifact staged):
cd koru-application && npm run pixel:model:stage && npm run pixel:model:push && cd ..
./submission-artifacts/benchmarks/run_device_benchmark.sh 3
# → pulls accelerator-comparison-report.json and re-renders the .md + .svg
```

After the benchmark runs green, the dashed "PENDING" bars in
`docs/blog/04-gemma-in-the-cockpit.html` can be swapped for the freshly rendered
`accelerator-comparison-report.svg`.

> Heads-up on the model: the current `.litertlm` is rejected for native token
> generation (`tf_lite_audio_adapter`). Re-export Gemma 4 E2B **text-only** before the
> GPU/CPU lanes will report real numbers — details in `docs/audit-2026-06-08.md`.
