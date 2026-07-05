#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

AVD_NAME="${AVD_NAME:-txq_api34_atd}"
PACKAGE_NAME="io.github.leonarddon.quanttrading"
TEST_PACKAGE_NAME="${PACKAGE_NAME}.test"
DEFAULT_TEST_CLASSES="io.github.leonarddon.quanttrading.ui.FeatureTourInstrumentedTest,io.github.leonarddon.quanttrading.ui.AuthAccountLifecycleInstrumentedTest"
TEST_CLASS="${TEST_CLASS:-$DEFAULT_TEST_CLASSES}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/txq-feature-tour.XXXXXX")"
STARTED_EMULATOR=0
SERIAL="${SERIAL:-}"
SDK_DIR=""
EMULATOR_HEADLESS="${EMULATOR_HEADLESS:-1}"
EMULATOR_GPU="${EMULATOR_GPU:-swiftshader_indirect}"
EMULATOR_BOOT_TIMEOUT_SECONDS="${EMULATOR_BOOT_TIMEOUT_SECONDS:-300}"
ADB_WAIT_TIMEOUT_SECONDS="${ADB_WAIT_TIMEOUT_SECONDS:-180}"

cleanup() {
  local exit_code=$?
  if [[ "$STARTED_EMULATOR" == "1" && -n "$SERIAL" ]]; then
    "$SDK_DIR/platform-tools/adb" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    sleep 2
  fi
  if [[ "${KEEP_FEATURE_TOUR_WORK_DIR:-0}" != "1" ]]; then
    rm -rf "$WORK_DIR"
  else
    echo "Feature tour work dir: $WORK_DIR"
  fi
  exit "$exit_code"
}
trap cleanup EXIT

resolve_sdk_dir() {
  if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    SDK_DIR="$ANDROID_HOME"
    return
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then
    SDK_DIR="$ANDROID_SDK_ROOT"
    return
  fi
  if [[ -f local.properties ]]; then
    local configured
    configured="$(sed -n 's/^sdk.dir=//p' local.properties | head -1)"
    if [[ -n "$configured" && -x "$configured/platform-tools/adb" ]]; then
      SDK_DIR="$configured"
      return
    fi
  fi
  local fallback="/opt/homebrew/share/android-commandlinetools"
  if [[ -x "$fallback/platform-tools/adb" ]]; then
    SDK_DIR="$fallback"
    return
  fi
  echo "Android SDK with platform-tools/adb was not found." >&2
  exit 1
}

setup_java_home() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    return
  fi
  local homebrew_jdk="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  if [[ -d "$homebrew_jdk" ]]; then
    export JAVA_HOME="$homebrew_jdk"
    return
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
}

first_connected_device() {
  "$SDK_DIR/platform-tools/adb" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }'
}

run_with_timeout() {
  local seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$seconds" "$@"
  elif command -v gtimeout >/dev/null 2>&1; then
    gtimeout "$seconds" "$@"
  else
    "$@" &
    local pid=$!
    local elapsed=0
    while kill -0 "$pid" 2>/dev/null; do
      if [[ $elapsed -ge $seconds ]]; then
        kill -TERM "$pid" 2>/dev/null || true
        sleep 1
        kill -KILL "$pid" 2>/dev/null || true
        return 124
      fi
      sleep 1
      elapsed=$(( elapsed + 1 ))
    done
    wait "$pid"
  fi
}

wait_for_boot() {
  if ! run_with_timeout "$ADB_WAIT_TIMEOUT_SECONDS" \
    "$SDK_DIR/platform-tools/adb" -s "$SERIAL" wait-for-device; then
    echo "Timed out waiting for adb device $SERIAL after ${ADB_WAIT_TIMEOUT_SECONDS}s" >&2
    exit 1
  fi
  local deadline=$(( $(date +%s) + EMULATOR_BOOT_TIMEOUT_SECONDS ))
  until [[ "$("$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    if (( $(date +%s) >= deadline )); then
      echo "Emulator $SERIAL did not finish booting within ${EMULATOR_BOOT_TIMEOUT_SECONDS}s" >&2
      exit 1
    fi
    sleep 2
  done
}

resolve_sdk_dir
setup_java_home

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME is not set and a local JDK 17 was not found." >&2
  exit 1
fi

if [[ -z "$SERIAL" ]]; then
  SERIAL="$(first_connected_device)"
fi

if [[ -z "$SERIAL" ]]; then
  if ! "$SDK_DIR/emulator/emulator" -list-avds | grep -qx "$AVD_NAME"; then
    echo "AVD not found: $AVD_NAME" >&2
    exit 1
  fi
  echo "== Starting emulator: $AVD_NAME =="
  emulator_args=(
    -avd "$AVD_NAME"
    -gpu "$EMULATOR_GPU"
    -no-snapshot
    -no-audio
    -no-boot-anim
    -netdelay none
    -netspeed full
  )
  if [[ "$EMULATOR_HEADLESS" == "1" ]]; then
    emulator_args+=(-no-window)
  fi
  nohup "$SDK_DIR/emulator/emulator" \
    "${emulator_args[@]}" \
    > "$WORK_DIR/emulator.log" 2>&1 &
  STARTED_EMULATOR=1
  if ! run_with_timeout "$ADB_WAIT_TIMEOUT_SECONDS" \
    "$SDK_DIR/platform-tools/adb" wait-for-device; then
    echo "Emulator did not register with adb within ${ADB_WAIT_TIMEOUT_SECONDS}s" >&2
    sed -n '1,80p' "$WORK_DIR/emulator.log" >&2 || true
    exit 1
  fi
  SERIAL="$(first_connected_device)"
fi

if [[ -z "$SERIAL" ]]; then
  echo "No adb device is available." >&2
  exit 1
fi

echo "== Waiting for boot: $SERIAL =="
wait_for_boot

echo "== Building debug and feature-tour test APKs =="
"$ROOT_DIR/gradlew" :app:assembleDebug :app:assembleDebugAndroidTest --console=plain

echo "== Installing app and test APK =="
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell pm clear "$PACKAGE_NAME" >/dev/null
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" logcat -c

echo "== Running Android feature tour =="
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell am instrument -w -r \
  -e class "$TEST_CLASS" \
  "$TEST_PACKAGE_NAME/androidx.test.runner.AndroidJUnitRunner" | tee "$WORK_DIR/instrumentation.log"

if ! grep -q "^OK (" "$WORK_DIR/instrumentation.log"; then
  echo "Feature tour instrumentation did not report OK." >&2
  exit 1
fi

echo "== Checking crash buffer =="
CRASH_LOG="$WORK_DIR/crash.log"
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" logcat -d -b crash > "$CRASH_LOG"
if [[ -s "$CRASH_LOG" ]]; then
  echo "Crash buffer is not empty:" >&2
  cat "$CRASH_LOG" >&2
  exit 1
fi

echo "Android feature tour verification passed."
