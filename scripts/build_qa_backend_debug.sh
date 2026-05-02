#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API_BASE_URL="${TIANXIAN_API_BASE_URL:-http://10.0.2.2:8080/}"

cd "$ROOT_DIR/TianXianQuant"

if [[ -z "${JAVA_HOME:-}" ]]; then
  HOMEBREW_JDK="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  if [[ -d "$HOMEBREW_JDK" ]]; then
    export JAVA_HOME="$HOMEBREW_JDK"
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME is not set and a local JDK 17 was not found." >&2
  exit 1
fi

./gradlew :app:assembleDebug \
  -PtianxianBackendSyncEnabled=true \
  -PtianxianRequireBackendPaymentSync=true \
  -PtianxianApiBaseUrl="$API_BASE_URL" \
  --console=plain
