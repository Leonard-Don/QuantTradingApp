#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "== Backend contract verification =="
scripts/verify_backend.sh

echo "== Android P0 verification =="
QuantTradingApp/scripts/verify_p0.sh

echo "== Android backend-sync compile =="
(
  cd QuantTradingApp
  if [[ -z "${JAVA_HOME:-}" ]]; then
    HOMEBREW_JDK="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    if [[ -d "$HOMEBREW_JDK" ]]; then
      export JAVA_HOME="$HOMEBREW_JDK"
    elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
      export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    fi
  fi
  ./gradlew :app:compileDebugKotlin \
    -PquanttradingBackendSyncEnabled=true \
    -PquanttradingApiBaseUrl=http://10.0.2.2:8080/ \
    --console=plain
)

echo "== Android release artifacts =="
QuantTradingApp/scripts/build_release_artifacts.sh
