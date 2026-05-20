#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${1:-$ROOT_DIR/store_assets/screenshots}"

mkdir -p "$OUTPUT_DIR"

echo "Capturing store screenshots into: $OUTPUT_DIR"
EMULATOR_HEADLESS="${EMULATOR_HEADLESS:-0}" EMULATOR_GPU="${EMULATOR_GPU:-host}" SCREENSHOT_DIR="$OUTPUT_DIR" "$ROOT_DIR/QuantTradingApp/scripts/verify_emulator_smoke.sh"

cat > "$OUTPUT_DIR/README.md" <<'EOF'
# QuantTradingApp Store Screenshots

Run `scripts/capture_store_screenshots.sh` to capture screenshots from the emulator smoke path.

The capture script validates that each PNG is not blank or black. On machines where Android Emulator's `adb screencap` returns a black frame, the script exits non-zero and does not leave store-ready screenshots here.

Expected files:

- `01_stock_home.png`
- `02_review.png`
- `03_community.png`
- `04_quant.png`
- `05_vip.png`
- `06_auth.png`

Before store upload, recapture these from the signed store-test candidate build and review them on a real device.
EOF
