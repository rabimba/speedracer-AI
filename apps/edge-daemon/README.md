# Edge Daemon

The PRD names `apps/edge-daemon` as the in-car runtime. In this repository the working edge runtime is the native Android app in `pixel-android-app`, so this directory is an ownership alias rather than a duplicate implementation.

Implementation source:

- Android cockpit and hot path: `../../pixel-android-app`
- Hardware parsers and foreground service: `../../pixel-android-app/app/src/main/java/com/trustableai/koru/service`
- Edge inference and audio policy: `../../pixel-android-app/app/src/main/java/com/trustableai/koru/runtime`

Use the root CI workflow or the Android project directly for build and test commands.
