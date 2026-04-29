#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

AVD_NAME="${AVD_NAME:-txq_api34_atd}"
PACKAGE_NAME="com.tianxian.quant"
MAIN_ACTIVITY="com.tianxian.quant/.MainActivity"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/txq-emulator-smoke.XXXXXX")"
STARTED_EMULATOR=0
SERIAL="${SERIAL:-}"
SDK_DIR=""

cleanup() {
  local exit_code=$?
  if [[ "$STARTED_EMULATOR" == "1" && -n "$SERIAL" ]]; then
    "$SDK_DIR/platform-tools/adb" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    sleep 2
  fi
  rm -rf "$WORK_DIR"
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

wait_for_boot() {
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" wait-for-device
  until [[ "$("$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
  done
}

dump_ui() {
  local output="$1"
  local raw_output="${output}.raw"
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" exec-out uiautomator dump /dev/tty > "$raw_output"
  python3 - "$raw_output" "$output" <<'PY'
import sys

raw_file, output_file = sys.argv[1:3]
text = open(raw_file, "r", encoding="utf-8", errors="replace").read()
start = text.find("<?xml")
if start < 0:
    start = text.find("<hierarchy")
end = text.rfind("</hierarchy>")
if start < 0 or end < 0:
    sys.stderr.write(text[:2000])
    sys.exit(1)
end += len("</hierarchy>")
open(output_file, "w", encoding="utf-8").write(text[start:end])
PY
}

node_center() {
  local xml_file="$1"
  local attr_name="$2"
  local value="$3"
  python3 - "$xml_file" "$attr_name" "$value" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

xml_file, attr_name, value = sys.argv[1:4]
root = ET.parse(xml_file).getroot()
for node in root.iter():
    if node.attrib.get(attr_name) == value:
        bounds = node.attrib.get("bounds", "")
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
            print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
            sys.exit(0)
sys.exit(1)
PY
}

has_node() {
  local xml_file="$1"
  local attr_name="$2"
  local value="$3"
  python3 - "$xml_file" "$attr_name" "$value" <<'PY'
import sys
import xml.etree.ElementTree as ET

xml_file, attr_name, value = sys.argv[1:4]
root = ET.parse(xml_file).getroot()
for node in root.iter():
    if node.attrib.get(attr_name) == value:
        sys.exit(0)
sys.exit(1)
PY
}

assert_node() {
  local xml_file="$1"
  local attr_name="$2"
  local value="$3"
  if ! has_node "$xml_file" "$attr_name" "$value"; then
    echo "Expected UI node not found: $attr_name=$value" >&2
    sed -n '1,80p' "$xml_file" >&2
    exit 1
  fi
}

tap_node() {
  local xml_file="$1"
  local attr_name="$2"
  local value="$3"
  local xy
  if ! xy="$(node_center "$xml_file" "$attr_name" "$value")"; then
    echo "Cannot tap missing UI node: $attr_name=$value" >&2
    exit 1
  fi
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell input tap $xy
}

assert_focus_contains() {
  local expected="$1"
  if ! "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell dumpsys window | grep -q "$expected"; then
    echo "Focused window does not contain $expected" >&2
    "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" >&2 || true
    exit 1
  fi
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
  nohup "$SDK_DIR/emulator/emulator" \
    -avd "$AVD_NAME" \
    -no-window \
    -gpu swiftshader_indirect \
    -no-snapshot \
    -no-audio \
    -no-boot-anim \
    -netdelay none \
    -netspeed full \
    > "$WORK_DIR/emulator.log" 2>&1 &
  STARTED_EMULATOR=1
  "$SDK_DIR/platform-tools/adb" wait-for-device
  SERIAL="$(first_connected_device)"
fi

if [[ -z "$SERIAL" ]]; then
  echo "No adb device is available." >&2
  exit 1
fi

echo "== Waiting for boot: $SERIAL =="
wait_for_boot

echo "== Building debug APK =="
"$ROOT_DIR/gradlew" :app:assembleDebug --console=plain

echo "== Installing app =="
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
if [[ "${PRESERVE_APP_DATA:-0}" != "1" ]]; then
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell pm clear "$PACKAGE_NAME" >/dev/null
fi
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" logcat -c

echo "== Launching app =="
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell am start -n "$MAIN_ACTIVITY" >/dev/null
sleep 3

MAIN_XML="$WORK_DIR/main.xml"
dump_ui "$MAIN_XML"
assert_focus_contains "com.tianxian.quant/.MainActivity"
assert_node "$MAIN_XML" "content-desc" "选股"
assert_node "$MAIN_XML" "content-desc" "复盘"
assert_node "$MAIN_XML" "content-desc" "社区"
assert_node "$MAIN_XML" "content-desc" "量化"

echo "== Checking main tabs =="
tap_node "$MAIN_XML" "content-desc" "复盘"
sleep 1
REVIEW_XML="$WORK_DIR/review.xml"
dump_ui "$REVIEW_XML"
assert_node "$REVIEW_XML" "text" "市场概况"
if ! has_node "$REVIEW_XML" "text" "自选体检"; then
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell input swipe 950 66 130 66 300
  sleep 1
  dump_ui "$REVIEW_XML"
fi
assert_node "$REVIEW_XML" "text" "自选体检"
tap_node "$REVIEW_XML" "content-desc" "自选体检"
sleep 1
dump_ui "$REVIEW_XML"
assert_node "$REVIEW_XML" "text" "VIP自选池体检"
if ! has_node "$REVIEW_XML" "text" "压力测试"; then
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell input swipe 950 66 130 66 300
  sleep 1
  dump_ui "$REVIEW_XML"
fi
assert_node "$REVIEW_XML" "text" "压力测试"
tap_node "$REVIEW_XML" "content-desc" "压力测试"
sleep 1
dump_ui "$REVIEW_XML"
assert_node "$REVIEW_XML" "text" "VIP自选池压力测试"
if ! has_node "$REVIEW_XML" "text" "研究简报"; then
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell input swipe 950 66 130 66 300
  sleep 1
  dump_ui "$REVIEW_XML"
fi
assert_node "$REVIEW_XML" "text" "研究简报"
tap_node "$REVIEW_XML" "content-desc" "研究简报"
sleep 1
dump_ui "$REVIEW_XML"
assert_node "$REVIEW_XML" "text" "VIP每日研究简报"

tap_node "$REVIEW_XML" "content-desc" "社区"
sleep 1
COMMUNITY_XML="$WORK_DIR/community.xml"
dump_ui "$COMMUNITY_XML"
assert_node "$COMMUNITY_XML" "content-desc" "发布帖子"
for _ in 1 2 3 4; do
  if has_node "$COMMUNITY_XML" "text" "消费板块估值分位笔记"; then
    break
  fi
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell input swipe 540 1750 540 1000 300
  sleep 0.5
  dump_ui "$COMMUNITY_XML"
done
assert_node "$COMMUNITY_XML" "text" "消费板块估值分位笔记"
tap_node "$COMMUNITY_XML" "text" "消费板块估值分位笔记"
sleep 1
COMMUNITY_DETAIL_XML="$WORK_DIR/community-detail.xml"
dump_ui "$COMMUNITY_DETAIL_XML"
assert_node "$COMMUNITY_DETAIL_XML" "text" "研究纪要(VIP)"
tap_node "$COMMUNITY_DETAIL_XML" "text" "关闭"
sleep 1

tap_node "$COMMUNITY_XML" "content-desc" "量化"
sleep 1
QUANT_XML="$WORK_DIR/quant.xml"
dump_ui "$QUANT_XML"
assert_node "$QUANT_XML" "text" "模型信号观察"
assert_node "$QUANT_XML" "text" "模型诊断(VIP)"
assert_node "$QUANT_XML" "text" "研究模型"

tap_node "$QUANT_XML" "content-desc" "选股"
sleep 1
STOCK_XML="$WORK_DIR/stock.xml"
dump_ui "$STOCK_XML"
assert_node "$STOCK_XML" "text" "搜索股票代码或名称"
for _ in 1 2 3 4 5; do
  if has_node "$STOCK_XML" "resource-id" "com.tianxian.quant:id/tvStockName"; then
    break
  fi
  sleep 1
  dump_ui "$STOCK_XML"
done
assert_node "$STOCK_XML" "resource-id" "com.tianxian.quant:id/tvStockName"
tap_node "$STOCK_XML" "resource-id" "com.tianxian.quant:id/tvStockName"
sleep 1
STOCK_DETAIL_XML="$WORK_DIR/stock-detail.xml"
dump_ui "$STOCK_DETAIL_XML"
assert_node "$STOCK_DETAIL_XML" "text" "深度诊断(VIP)"
tap_node "$STOCK_DETAIL_XML" "text" "关闭"
sleep 1

echo "== Checking VIP and auth path =="
VIP_CHIP_XML="$WORK_DIR/vip-chip.xml"
for _ in 1 2 3 4 5; do
  dump_ui "$VIP_CHIP_XML"
  if has_node "$VIP_CHIP_XML" "text" "高级多因子(VIP)"; then
    break
  fi
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell input swipe 930 322 150 322 300
  sleep 0.5
done
assert_node "$VIP_CHIP_XML" "text" "高级多因子(VIP)"
tap_node "$VIP_CHIP_XML" "text" "高级多因子(VIP)"
sleep 2

VIP_XML="$WORK_DIR/vip.xml"
dump_ui "$VIP_XML"
assert_focus_contains "com.tianxian.quant/.ui.vip.VipActivity"
assert_node "$VIP_XML" "text" "开通VIP会员"
assert_node "$VIP_XML" "text" "登录/注册"
tap_node "$VIP_XML" "text" "登录/注册"
sleep 2

AUTH_XML="$WORK_DIR/auth.xml"
dump_ui "$AUTH_XML"
assert_focus_contains "com.tianxian.quant/.ui.auth.AuthActivity"
assert_node "$AUTH_XML" "text" "登录/注册"
assert_node "$AUTH_XML" "text" "账号状态"
assert_node "$AUTH_XML" "text" "手机号"
assert_node "$AUTH_XML" "text" "密码"
assert_node "$AUTH_XML" "text" "开启每日研究提醒"

echo "== Checking crash buffer =="
CRASH_LOG="$WORK_DIR/crash.log"
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" logcat -d -b crash > "$CRASH_LOG"
if [[ -s "$CRASH_LOG" ]]; then
  echo "Crash buffer is not empty:" >&2
  cat "$CRASH_LOG" >&2
  exit 1
fi

echo "Emulator smoke verification passed."
