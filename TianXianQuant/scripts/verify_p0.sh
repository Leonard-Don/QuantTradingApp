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

LINT_XML_REPORT="$ROOT_DIR/app/build/reports/lint-results-debug.xml"
if [[ ! -f "$LINT_XML_REPORT" ]]; then
  echo "Lint XML report not found: $LINT_XML_REPORT" >&2
  echo "Expected :app:lintDebug to emit a structured XML report; aborting P0 gate." >&2
  exit 1
fi

if ! LINT_ISSUE_COUNT="$(xmllint --xpath 'count(//issue)' "$LINT_XML_REPORT" 2>/dev/null)"; then
  echo "Failed to parse lint XML report: $LINT_XML_REPORT" >&2
  exit 1
fi

LINT_ISSUE_COUNT="${LINT_ISSUE_COUNT//[[:space:]]/}"
if [[ ! "$LINT_ISSUE_COUNT" =~ ^[0-9]+$ ]]; then
  echo "Unexpected issue count from lint XML: '$LINT_ISSUE_COUNT'" >&2
  exit 1
fi

if (( LINT_ISSUE_COUNT > 0 )); then
  echo "lintDebug reported $LINT_ISSUE_COUNT issue(s) in $LINT_XML_REPORT:" >&2
  { xmllint --format "$LINT_XML_REPORT" 2>/dev/null || sed -n '1,200p' "$LINT_XML_REPORT"; } >&2 || true
  exit 1
fi

echo "P0 verification passed (lintDebug: 0 issues)."
