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
SCREENSHOT_DIR="${SCREENSHOT_DIR:-}"
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

node_tap_points() {
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
    if node.attrib.get(attr_name) != value:
        continue
    bounds = node.attrib.get("bounds", "")
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not match:
        continue
    x1, y1, x2, y2 = map(int, match.groups())
    x = (x1 + x2) // 2
    candidates = [
        (y1 + y2) // 2,
        max(y1 + 1, min(y2 - 1, y2 - max(8, (y2 - y1) // 5))),
        max(y1 + 1, min(y2 - 1, y1 + max(8, (y2 - y1) // 5))),
    ]
    seen = set()
    for y in candidates:
        if y not in seen:
            seen.add(y)
            print(f"{x} {y}")
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

has_text_contains() {
  local xml_file="$1"
  local value="$2"
  python3 - "$xml_file" "$value" <<'PY'
import sys
import xml.etree.ElementTree as ET

xml_file, value = sys.argv[1:3]
root = ET.parse(xml_file).getroot()
for node in root.iter():
    if value in node.attrib.get("text", ""):
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

assert_text_contains() {
  local xml_file="$1"
  local value="$2"
  if ! has_text_contains "$xml_file" "$value"; then
    echo "Expected UI text containing not found: $value" >&2
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

tap_node_points() {
  local xml_file="$1"
  local attr_name="$2"
  local value="$3"
  local points
  if ! points="$(node_tap_points "$xml_file" "$attr_name" "$value")"; then
    echo "Cannot tap missing UI node: $attr_name=$value" >&2
    exit 1
  fi
  while read -r xy; do
    [[ -z "$xy" ]] && continue
    "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell input tap $xy
    sleep 0.3
  done <<< "$points"
}

swipe_review_tabs_left() {
  local center
  local y=240
  if center="$(node_center "$REVIEW_XML" "resource-id" "com.tianxian.quant:id/tabLayout")"; then
    y="${center##* }"
  fi
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell input swipe 950 "$y" 130 "$y" 300
}

swipe_stock_filters_left() {
  local center
  local y=500
  if center="$(node_center "$VIP_CHIP_XML" "resource-id" "com.tianxian.quant:id/filterChips")"; then
    y="${center##* }"
  fi
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell input swipe 930 "$y" 150 "$y" 300
}

capture_screenshot() {
  local name="$1"
  if [[ -z "$SCREENSHOT_DIR" ]]; then
    return
  fi
  mkdir -p "$SCREENSHOT_DIR"
  local output="$SCREENSHOT_DIR/$name.png"
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" exec-out screencap -p > "$output"
  python3 - "$output" <<'PY'
from pathlib import Path
import sys

from PIL import Image

path = Path(sys.argv[1])
with Image.open(path).convert("RGB") as image:
    thumb = image.resize((64, 64))
    colors = thumb.getcolors(maxcolors=4096) or []
    non_black = sum(count for count, color in colors if color != (0, 0, 0))
    total = sum(count for count, _ in colors)
    if total == 0 or non_black / total < 0.05:
        raise SystemExit(f"Screenshot appears blank or black: {path}")
PY
  echo "Captured screenshot: $output"
}

select_review_tab() {
  local label="$1"
  local expected_title="$2"
  for attempt in 1 2 3; do
    if ! has_node "$REVIEW_XML" "text" "$label"; then
      swipe_review_tabs_left
      sleep 1
      dump_ui "$REVIEW_XML"
    fi
    assert_node "$REVIEW_XML" "text" "$label"
    tap_node_points "$REVIEW_XML" "text" "$label"
    sleep 2
    dump_ui "$REVIEW_XML"
    if has_node "$REVIEW_XML" "text" "$expected_title"; then
      return
    fi
  done
  echo "Review tab did not open: $label -> $expected_title" >&2
  sed -n '1,120p' "$REVIEW_XML" >&2
  exit 1
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

echo "== Building debug APK =="
"$ROOT_DIR/gradlew" :app:assembleDebug --console=plain

echo "== Installing app =="
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
if [[ "${PRESERVE_APP_DATA:-0}" != "1" ]]; then
  "$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell pm clear "$PACKAGE_NAME" >/dev/null
fi
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" logcat -c

echo "== Launching app =="
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell am start -n "$MAIN_ACTIVITY" >/dev/null
sleep 3

MAIN_XML="$WORK_DIR/main.xml"
dump_ui "$MAIN_XML"
assert_focus_contains "com.tianxian.quant/.MainActivity"
assert_node "$MAIN_XML" "content-desc" "选股"
assert_node "$MAIN_XML" "content-desc" "复盘"
assert_node "$MAIN_XML" "content-desc" "社区"
assert_node "$MAIN_XML" "content-desc" "量化"
assert_node "$MAIN_XML" "content-desc" "账号与VIP权益"

echo "== Checking account entry =="
tap_node "$MAIN_XML" "content-desc" "账号与VIP权益"
sleep 1
ACCOUNT_XML="$WORK_DIR/account-vip.xml"
dump_ui "$ACCOUNT_XML"
assert_focus_contains "com.tianxian.quant/.ui.vip.VipActivity"
assert_node "$ACCOUNT_XML" "text" "开通VIP会员"
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" shell input keyevent KEYCODE_BACK
sleep 1
dump_ui "$MAIN_XML"
assert_focus_contains "com.tianxian.quant/.MainActivity"

echo "== Checking main tabs =="
tap_node "$MAIN_XML" "content-desc" "复盘"
sleep 1
REVIEW_XML="$WORK_DIR/review.xml"
dump_ui "$REVIEW_XML"
assert_node "$REVIEW_XML" "text" "市场概况"
capture_screenshot "02_review"
select_review_tab "自选体检" "VIP自选池体检"
select_review_tab "持仓组合" "VIP持仓组合"
select_review_tab "压力测试" "VIP自选池压力测试"
select_review_tab "研究简报" "VIP每日研究简报"
select_review_tab "研究计划" "VIP研究计划"

tap_node "$REVIEW_XML" "content-desc" "社区"
sleep 1
COMMUNITY_XML="$WORK_DIR/community.xml"
dump_ui "$COMMUNITY_XML"
assert_node "$COMMUNITY_XML" "content-desc" "发布帖子"
capture_screenshot "03_community"
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
capture_screenshot "04_quant"

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
assert_text_contains "$STOCK_XML" "行情池概览"
assert_text_contains "$STOCK_XML" "涨幅榜"
assert_text_contains "$STOCK_XML" "热门板块"
capture_screenshot "01_stock_home"
tap_node "$STOCK_XML" "resource-id" "com.tianxian.quant:id/tvStockName"
sleep 1
STOCK_DETAIL_XML="$WORK_DIR/stock-detail.xml"
dump_ui "$STOCK_DETAIL_XML"
assert_node "$STOCK_DETAIL_XML" "text" "深度诊断(VIP)"
assert_node "$STOCK_DETAIL_XML" "text" "目标价提醒"
assert_text_contains "$STOCK_DETAIL_XML" "指标强度"
assert_node "$STOCK_DETAIL_XML" "text" "风险雷达"
assert_node "$STOCK_DETAIL_XML" "text" "后续研究动作"
tap_node "$STOCK_DETAIL_XML" "text" "目标价提醒"
sleep 1
PRICE_ALERT_XML="$WORK_DIR/price-alert.xml"
dump_ui "$PRICE_ALERT_XML"
assert_text_contains "$PRICE_ALERT_XML" "目标价提醒"
assert_text_contains "$PRICE_ALERT_XML" "本机"
assert_node "$PRICE_ALERT_XML" "text" "上穿目标价"
assert_node "$PRICE_ALERT_XML" "text" "保存提醒"
tap_node "$PRICE_ALERT_XML" "text" "取消"
sleep 1

echo "== Checking VIP and auth path =="
VIP_CHIP_XML="$WORK_DIR/vip-chip.xml"
for _ in 1 2 3 4 5; do
  dump_ui "$VIP_CHIP_XML"
  if has_node "$VIP_CHIP_XML" "text" "高级多因子(VIP)"; then
    break
  fi
  swipe_stock_filters_left
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
capture_screenshot "05_vip"
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
capture_screenshot "06_auth"

echo "== Checking crash buffer =="
CRASH_LOG="$WORK_DIR/crash.log"
"$SDK_DIR/platform-tools/adb" -s "$SERIAL" logcat -d -b crash > "$CRASH_LOG"
if [[ -s "$CRASH_LOG" ]]; then
  echo "Crash buffer is not empty:" >&2
  cat "$CRASH_LOG" >&2
  exit 1
fi

echo "Emulator smoke verification passed."
