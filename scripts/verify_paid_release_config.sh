#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/scripts/check_paid_release_inputs.sh"

cd "$ROOT_DIR/QuantTradingApp"

if [[ -z "${JAVA_HOME:-}" ]]; then
  HOMEBREW_JDK="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  if [[ -d "$HOMEBREW_JDK" ]]; then
    export JAVA_HOME="$HOMEBREW_JDK"
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
fi

args=(
  :app:verifyPaidReleaseConfig
  -PquanttradingBackendSyncEnabled=true
  -PquanttradingRequireBackendPaymentSync=true
  "-PquanttradingApiBaseUrl=${QUANTTRADING_PRODUCTION_API_BASE_URL:-}"
  "-PquanttradingPrivacyPolicyUrl=${QUANTTRADING_PRIVACY_POLICY_URL:-}"
  "-PquanttradingTermsUrl=${QUANTTRADING_TERMS_URL:-}"
  "-PquanttradingDataDisclaimerUrl=${QUANTTRADING_DATA_DISCLAIMER_URL:-}"
  "-PquanttradingSupportEmail=${QUANTTRADING_SUPPORT_EMAIL:-}"
  --console=plain
)

# Keep release-signing values out of Gradle argv. Gradle reads
# ORG_GRADLE_PROJECT_* env vars as project properties, which keeps local
# keystore paths and passwords out of echoed command lines and failure output.
unset ORG_GRADLE_PROJECT_quanttradingReleaseKeystore
unset ORG_GRADLE_PROJECT_quanttradingReleaseStorePassword
unset ORG_GRADLE_PROJECT_quanttradingReleaseKeyAlias
unset ORG_GRADLE_PROJECT_quanttradingReleaseKeyPassword

if [[ -n "${QUANTTRADING_RELEASE_KEYSTORE:-}" ]]; then
  if [[ ! -f "$QUANTTRADING_RELEASE_KEYSTORE" ]]; then
    echo "QUANTTRADING_RELEASE_KEYSTORE points to a missing file" >&2
    exit 1
  fi
  export ORG_GRADLE_PROJECT_quanttradingReleaseKeystore="$QUANTTRADING_RELEASE_KEYSTORE"
fi
if [[ -n "${QUANTTRADING_RELEASE_STORE_PASSWORD:-}" ]]; then
  export ORG_GRADLE_PROJECT_quanttradingReleaseStorePassword="$QUANTTRADING_RELEASE_STORE_PASSWORD"
fi
if [[ -n "${QUANTTRADING_RELEASE_KEY_ALIAS:-}" ]]; then
  export ORG_GRADLE_PROJECT_quanttradingReleaseKeyAlias="$QUANTTRADING_RELEASE_KEY_ALIAS"
fi
if [[ -n "${QUANTTRADING_RELEASE_KEY_PASSWORD:-}" ]]; then
  export ORG_GRADLE_PROJECT_quanttradingReleaseKeyPassword="$QUANTTRADING_RELEASE_KEY_PASSWORD"
fi

redact_release_signing_output() {
  python3 -c '
import os
import sys

text = sys.stdin.read()
redactions = []
for name, replacement in (
    ("QUANTTRADING_RELEASE_KEYSTORE", "[redacted-keystore]"),
    ("QUANTTRADING_RELEASE_STORE_PASSWORD", "[redacted-store-password]"),
    ("QUANTTRADING_RELEASE_KEY_ALIAS", "[redacted-key-alias]"),
    ("QUANTTRADING_RELEASE_KEY_PASSWORD", "[redacted-key-password]"),
):
    value = os.environ.get(name)
    if value:
        redactions.append((value, replacement))

# Redact longer values first so overlapping secrets cannot leave suffixes behind
# (for example alias=foo and keyPassword=foo-bar).
for value, replacement in sorted(set(redactions), key=lambda item: len(item[0]), reverse=True):
    text = text.replace(value, replacement)
sys.stdout.write(text)
'
}

set +e
./gradlew "${args[@]}" 2>&1 | redact_release_signing_output
pipeline_status=("${PIPESTATUS[@]}")
gradle_status=${pipeline_status[0]}
redactor_status=${pipeline_status[1]}
set -e
if (( redactor_status != 0 )); then
  echo "Release output redactor failed" >&2
  exit "$redactor_status"
fi
exit "$gradle_status"
