#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "== Generating store assets =="
python3 scripts/generate_store_assets.py

echo "== Running local release verification =="
scripts/verify_all.sh

cat <<'EOF'

Local store-candidate preparation passed.

External production gates still require real account-controlled values:
- source release.env
- scripts/verify_paid_release_config.sh
- scripts/capture_store_screenshots.sh

Do not upload until privacy/terms/data-disclaimer URLs, support email, signing keys,
backend production secrets, merchant payment credentials, and licensed data-source
credentials are configured.
EOF
