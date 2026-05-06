#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_DIR="$(cd "$ROOT_DIR/.." && pwd)"
TARGET_PACKAGE="com.trustableai.koru.debug"
SIM_PACKAGE="com.trustableai.koru.simrunner"
SCENARIO="sonoma_beginner_training.v1"
PLAYBACK_SPEED="5.0"
DEVICE_SERIAL=""
SKIP_BUILD="false"

usage() {
  cat <<'USAGE'
Usage: ./run.sh [options]

Options:
  --scenario NAME       Scenario name without .json (default: sonoma_beginner_training.v1)
  --package PACKAGE     Target Koru Android package (default: com.trustableai.koru.debug)
  --device SERIAL       ADB device serial to use
  --playback-speed N    Mock-location playback speed multiplier (default: 5.0)
  --skip-build          Reuse already-built APKs
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario)
      SCENARIO="$2"
      shift 2
      ;;
    --package)
      TARGET_PACKAGE="$2"
      shift 2
      ;;
    --device)
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --playback-speed)
      PLAYBACK_SPEED="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

find_adb() {
  if [[ -n "${ADB:-}" && -x "${ADB:-}" ]]; then
    echo "$ADB"
  elif command -v adb >/dev/null 2>&1; then
    command -v adb
  elif [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
    echo "$HOME/Library/Android/sdk/platform-tools/adb"
  elif [[ -x "/opt/homebrew/bin/adb" ]]; then
    echo "/opt/homebrew/bin/adb"
  else
    echo "adb not found. Set ADB or install Android platform-tools." >&2
    exit 1
  fi
}

ADB_BIN="$(find_adb)"
ADB_CMD=("$ADB_BIN")
if [[ -n "$DEVICE_SERIAL" ]]; then
  ADB_CMD+=("-s" "$DEVICE_SERIAL")
else
  DEVICES_TEXT="$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {print $1}')"
  DEVICE_COUNT="$(printf '%s\n' "$DEVICES_TEXT" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [[ "$DEVICE_COUNT" != "1" ]]; then
    echo "Expected exactly one connected device, found $DEVICE_COUNT." >&2
    "$ADB_BIN" devices >&2
    exit 1
  fi
  DEVICE_SERIAL="$(printf '%s\n' "$DEVICES_TEXT" | sed '/^$/d' | head -1)"
  ADB_CMD+=("-s" "$DEVICE_SERIAL")
fi

MODEL="$("${ADB_CMD[@]}" shell getprop ro.product.model | tr -d '\r')"
ANDROID_VERSION="$("${ADB_CMD[@]}" shell getprop ro.build.version.release | tr -d '\r')"
if [[ "$MODEL" != *"Pixel"* ]]; then
  echo "Connected device is '$MODEL'. The harness is intended for a physical Pixel 10; continuing for explicit testability." >&2
fi

if [[ -z "${JAVA_HOME:-}" && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Library/Android/sdk" ]]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
if [[ -z "${ANDROID_SDK_ROOT:-}" && -n "${ANDROID_HOME:-}" ]]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

REPORT_DIR="$ROOT_DIR/reports/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$REPORT_DIR"
LOGCAT_PID=""
STARTED_AT_MS="$(date +%s000)"

cleanup() {
  set +e
  "${ADB_CMD[@]}" shell am force-stop "$SIM_PACKAGE" >/dev/null 2>&1
  if [[ -n "$LOGCAT_PID" ]]; then
    kill "$LOGCAT_PID" >/dev/null 2>&1
    wait "$LOGCAT_PID" >/dev/null 2>&1
  fi
}
trap cleanup EXIT

prepare_device() {
  "${ADB_CMD[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "${ADB_CMD[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  "${ADB_CMD[@]}" shell settings put secure screensaver_enabled 0 >/dev/null 2>&1 || true
  "${ADB_CMD[@]}" shell svc power stayon usb >/dev/null 2>&1 || true

  local trust_state
  trust_state="$("${ADB_CMD[@]}" shell dumpsys trust 2>/dev/null | tr -d '\r' || true)"
  local window_policy
  window_policy="$("${ADB_CMD[@]}" shell dumpsys window policy 2>/dev/null | tr -d '\r' || true)"
  if grep -q "deviceLocked=1" <<<"$trust_state" || \
    { grep -q "showing=true" <<<"$window_policy" && grep -q "secure=true" <<<"$window_policy"; }; then
    cat >&2 <<EOF
Pixel 10 is currently locked. Unlock the device, keep it on the home screen, then rerun:
  cd "$ROOT_DIR"
  ./run.sh --scenario "$SCENARIO" --package "$TARGET_PACKAGE" --device "$DEVICE_SERIAL"

The harness cannot unlock a secure Pixel through ADB. It did disable screensaver entry and set USB stay-awake for the next run.
EOF
    exit 3
  fi
}

echo "Report directory: $REPORT_DIR"
echo "Device: $DEVICE_SERIAL ($MODEL / Android $ANDROID_VERSION)"
prepare_device

if [[ "$SKIP_BUILD" != "true" ]]; then
  echo "Generating scenario fixture..."
  (cd "$ROOT_DIR" && npm run generate:scenario)

  echo "Building target Koru debug APK..."
  (cd "$WORKSPACE_DIR/pixel-android-app" && ./gradlew :app:assembleDebug)

  echo "Building simulator APK and instrumentation..."
  (cd "$ROOT_DIR" && "$WORKSPACE_DIR/pixel-android-app/gradlew" -p "$ROOT_DIR" :app:assembleDebug :app:assembleDebugAndroidTest)
fi

KORU_APK="$WORKSPACE_DIR/pixel-android-app/app/build/outputs/apk/debug/app-debug.apk"
SIM_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
SIM_TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

echo "Installing APKs..."
"${ADB_CMD[@]}" install -r -g "$KORU_APK" >/dev/null
"${ADB_CMD[@]}" install -r -g "$SIM_APK" >/dev/null
"${ADB_CMD[@]}" install -r "$SIM_TEST_APK" >/dev/null

echo "Resetting app state and granting permissions..."
"${ADB_CMD[@]}" shell pm clear "$TARGET_PACKAGE" >/dev/null || true
"${ADB_CMD[@]}" shell pm clear "$SIM_PACKAGE" >/dev/null || true
for permission in \
  android.permission.CAMERA \
  android.permission.ACCESS_FINE_LOCATION \
  android.permission.ACCESS_COARSE_LOCATION \
  android.permission.POST_NOTIFICATIONS
do
  "${ADB_CMD[@]}" shell pm grant "$TARGET_PACKAGE" "$permission" >/dev/null 2>&1 || true
done
for permission in \
  android.permission.ACCESS_FINE_LOCATION \
  android.permission.ACCESS_COARSE_LOCATION \
  android.permission.POST_NOTIFICATIONS
do
  "${ADB_CMD[@]}" shell pm grant "$SIM_PACKAGE" "$permission" >/dev/null 2>&1 || true
done
"${ADB_CMD[@]}" shell appops set "$SIM_PACKAGE" android:mock_location allow >/dev/null 2>&1 || \
  "${ADB_CMD[@]}" shell appops set "$SIM_PACKAGE" mock_location allow >/dev/null 2>&1 || true

cat > "$REPORT_DIR/metadata.json" <<EOF
{
  "startedAtMs": $STARTED_AT_MS,
  "deviceSerial": "$DEVICE_SERIAL",
  "deviceModel": "$MODEL",
  "androidVersion": "$ANDROID_VERSION",
  "targetPackage": "$TARGET_PACKAGE",
  "simPackage": "$SIM_PACKAGE",
  "scenario": "$SCENARIO",
  "playbackSpeed": "$PLAYBACK_SPEED"
}
EOF

echo "Clearing and capturing logcat..."
"${ADB_CMD[@]}" logcat -c
("${ADB_CMD[@]}" logcat -v time > "$REPORT_DIR/logcat.txt") &
LOGCAT_PID="$!"

echo "Running UI Automator training simulation..."
set +e
"${ADB_CMD[@]}" shell am instrument -w -r \
  -e targetPackage "$TARGET_PACKAGE" \
  -e scenario "$SCENARIO" \
  -e playbackSpeed "$PLAYBACK_SPEED" \
  "$SIM_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" | tee "$REPORT_DIR/instrumentation.txt"
INSTRUMENTATION_STATUS=${PIPESTATUS[0]}
set -e

"${ADB_CMD[@]}" shell dumpsys activity services "$TARGET_PACKAGE" > "$REPORT_DIR/dumpsys-services-after.txt" || true
sleep 1

echo "Pulling latest recorded session artifact..."
set +e
"${ADB_CMD[@]}" exec-out run-as "$TARGET_PACKAGE" sh -c 'latest=$(ls -t files/recorded_sessions/*.json 2>/dev/null | head -1); [ -n "$latest" ] && cat "$latest"' > "$REPORT_DIR/recorded-session.json"
PULL_STATUS=$?
set -e
if [[ "$PULL_STATUS" -ne 0 || ! -s "$REPORT_DIR/recorded-session.json" ]]; then
  echo "{}" > "$REPORT_DIR/recorded-session.json"
  echo "No recorded session artifact could be pulled." >&2
fi

echo "Validating report..."
set +e
node "$ROOT_DIR/tools/validate-report.mjs" \
  --scenario "$ROOT_DIR/app/src/main/assets/scenarios/$SCENARIO.json" \
  --artifact "$REPORT_DIR/recorded-session.json" \
  --logcat "$REPORT_DIR/logcat.txt" \
  --instrumentation "$REPORT_DIR/instrumentation.txt" \
  --metadata "$REPORT_DIR/metadata.json" \
  --out "$REPORT_DIR"
VALIDATION_STATUS=$?
set -e

echo "Report: $REPORT_DIR/report.md"
if [[ "$INSTRUMENTATION_STATUS" -ne 0 ]]; then
  echo "Instrumentation failed with status $INSTRUMENTATION_STATUS" >&2
  exit "$INSTRUMENTATION_STATUS"
fi
exit "$VALIDATION_STATUS"
