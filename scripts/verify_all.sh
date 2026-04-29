#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "== Backend contract verification =="
scripts/verify_backend.sh

echo "== Android P0 verification =="
TianXianQuant/scripts/verify_p0.sh

echo "== Android release artifacts =="
TianXianQuant/scripts/build_release_artifacts.sh
