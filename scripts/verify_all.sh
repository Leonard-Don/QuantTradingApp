#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "== Android P0 verification =="
QuantTradingApp/scripts/verify_p0.sh

echo "== Android release artifacts =="
QuantTradingApp/scripts/build_release_artifacts.sh
