# TianXianQuant Store Screenshots

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
