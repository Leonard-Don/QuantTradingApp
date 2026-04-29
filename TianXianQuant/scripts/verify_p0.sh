#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

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

if ! command -v xmllint >/dev/null 2>&1; then
  echo "xmllint is required for XML validation." >&2
  exit 1
fi

echo "== XML validation =="
xmllint --noout app/lint.xml
find app/src/main/res -name '*.xml' -print0 | xargs -0 xmllint --noout

echo "== Gradle P0 verification =="
"$ROOT_DIR/gradlew" \
  :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleRelease \
  --console=plain

LINT_REPORT="$ROOT_DIR/app/build/reports/lint-results-debug.txt"
if [[ ! -f "$LINT_REPORT" ]]; then
  echo "Lint report not found: $LINT_REPORT" >&2
  exit 1
fi

if ! grep -q "No issues found." "$LINT_REPORT"; then
  echo "lintDebug completed, but the text report still contains issues:" >&2
  sed -n '1,120p' "$LINT_REPORT" >&2
  exit 1
fi

echo "P0 verification passed."
