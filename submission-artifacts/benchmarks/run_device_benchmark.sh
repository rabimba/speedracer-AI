#!/usr/bin/env bash
# =============================================================================
# Racecraft / speedracer-AI — real on-device GPU vs CPU vs NPU benchmark runner
# =============================================================================
# Produces a REAL accelerator-comparison-report.json from a connected Pixel.
# This cannot run in a headless CI/sandbox: it needs a physical device, the
# Android SDK (adb), a built debug APK, and a native-ready Gemma 4 E2B model.
#
# Prerequisites (on your machine, with the Pixel connected + unlocked):
#   1. Android SDK platform-tools on PATH (adb), or set ANDROID_HOME / ADB.
#   2. Node.js available for staging model metadata.
#   3. The Pixel connected + unlocked.
#   4. Google Tensor dispatch library packaged at
#      pixel-android-app/app/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_GoogleTensor.so.
#
# Usage:
#   ./run_device_benchmark.sh [runsPerStrategy]   # default 3
# =============================================================================
set -euo pipefail

RUNS="${1:-3}"
ADB="${ADB:-adb}"
TEST_PKG="${TEST_PKG:-com.trustableai.koru.debug.test}"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="com.trustableai.koru.runtime.benchmark.AcceleratorComparisonInstrumentedTest"
NPU_NATIVE_LIBRARY_DIR_ARG="${NPU_NATIVE_LIBRARY_DIR:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOCAL_REPORT="${SCRIPT_DIR}/accelerator-comparison-report.json"
PARTIALS_DIR="${SCRIPT_DIR}/partials"
PACKAGED_NPU_DISPATCH_LIB="${REPO_ROOT}/pixel-android-app/app/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"

echo ">> Building and installing debug + androidTest APKs"
(cd "${REPO_ROOT}/pixel-android-app" && ./gradlew :app:installDebug :app:installDebugAndroidTest)

echo ">> Staging and pushing LiteRT-LM model artifacts"
(cd "${REPO_ROOT}/koru-application" && npm run pixel:model:stage && ADB="${ADB}" npm run pixel:model:push)

echo ">> Checking packaged Google Tensor NPU dispatch runtime"
if [[ -f "${PACKAGED_NPU_DISPATCH_LIB}" ]]; then
  echo "   ${PACKAGED_NPU_DISPATCH_LIB}"
else
  echo ">> WARNING: packaged Google Tensor NPU dispatch runtime not found."
  echo "   NPU will fail unless the app APK includes libLiteRtDispatch_GoogleTensor.so"
fi
if [[ -n "${NPU_NATIVE_LIBRARY_DIR_ARG}" ]]; then
  echo ">> Overriding NPU native library dir with ${NPU_NATIVE_LIBRARY_DIR_ARG}"
fi

echo ">> Device check"
"${ADB}" wait-for-device
"${ADB}" shell getprop ro.product.model

echo ">> Confirm model is present on device"
"${ADB}" shell 'ls -l /data/local/tmp/koru/models/gemma-4-e2b-it/ || echo "MODEL NOT STAGED — run npm run pixel:model:push"'

echo ">> Running AcceleratorComparisonInstrumentedTest one accelerator at a time (runsPerStrategy=${RUNS})"
# Each lane is isolated because NPU dispatch failures can abort the native process.
rm -rf "${PARTIALS_DIR}"
mkdir -p "${PARTIALS_DIR}"
for ACCELERATOR in GPU CPU NPU; do
  REPORT_SUFFIX="-$(echo "${ACCELERATOR}" | tr '[:upper:]' '[:lower:]')"
  REMOTE_LANE_REPORT="/sdcard/Download/koru-accelerator-comparison-report${REPORT_SUFFIX}.json"
  LOCAL_LANE_REPORT="${PARTIALS_DIR}/accelerator-comparison-report${REPORT_SUFFIX}.json"
  echo ">> Running ${ACCELERATOR} lane"
  "${ADB}" shell rm -f "${REMOTE_LANE_REPORT}" >/dev/null 2>&1 || true
  INSTRUMENT_CMD=(
    "${ADB}" shell am instrument -w
    -e class "${TEST_CLASS}#compareCpuGpuNpuTokenGenerationSpeed"
    -e runsPerStrategy "${RUNS}"
    -e accelerator "${ACCELERATOR}"
    -e reportSuffix "${REPORT_SUFFIX}"
  )
  if [[ "${ACCELERATOR}" == "NPU" && -n "${NPU_NATIVE_LIBRARY_DIR_ARG}" ]]; then
    INSTRUMENT_CMD+=(-e npuNativeLibraryDir "${NPU_NATIVE_LIBRARY_DIR_ARG}")
  fi
  INSTRUMENT_CMD+=("${TEST_PKG}/${RUNNER}")
  set +e
  "${INSTRUMENT_CMD[@]}"
  STATUS=$?
  set -e
  if [[ ${STATUS} -ne 0 ]]; then
    echo ">> ${ACCELERATOR} lane instrumentation exited with ${STATUS}; will merge as failed if no report was written"
  fi
  if "${ADB}" shell test -f "${REMOTE_LANE_REPORT}"; then
    "${ADB}" pull "${REMOTE_LANE_REPORT}" "${LOCAL_LANE_REPORT}"
  else
    echo ">> ${ACCELERATOR} lane did not write ${REMOTE_LANE_REPORT}"
  fi
done

echo ">> Merging lane reports"
python3 "${SCRIPT_DIR}/merge_accelerator_reports.py" --partials-dir "${PARTIALS_DIR}" --output "${LOCAL_REPORT}"

echo ">> Rendering Markdown + SVG"
python3 "${SCRIPT_DIR}/render_benchmark_report.py" "${LOCAL_REPORT}"

echo ">> Done. See:"
echo "   ${LOCAL_REPORT}"
echo "   ${LOCAL_REPORT%.json}.md"
echo "   ${LOCAL_REPORT%.json}.svg"
