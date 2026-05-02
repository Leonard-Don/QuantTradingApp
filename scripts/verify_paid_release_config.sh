#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR/TianXianQuant"

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
  -PtianxianBackendSyncEnabled=true
  -PtianxianRequireBackendPaymentSync=true
  "-PtianxianApiBaseUrl=${TIANXIAN_PRODUCTION_API_BASE_URL:-}"
  "-PtianxianPrivacyPolicyUrl=${TIANXIAN_PRIVACY_POLICY_URL:-}"
  "-PtianxianTermsUrl=${TIANXIAN_TERMS_URL:-}"
  "-PtianxianDataDisclaimerUrl=${TIANXIAN_DATA_DISCLAIMER_URL:-}"
  "-PtianxianSupportEmail=${TIANXIAN_SUPPORT_EMAIL:-}"
  --console=plain
)

if [[ -n "${TIANXIAN_RELEASE_KEYSTORE:-}" ]]; then
  if [[ ! -f "$TIANXIAN_RELEASE_KEYSTORE" ]]; then
    echo "TIANXIAN_RELEASE_KEYSTORE points to a missing file: $TIANXIAN_RELEASE_KEYSTORE" >&2
    exit 1
  fi
  args+=("-PtianxianReleaseKeystore=$TIANXIAN_RELEASE_KEYSTORE")
fi
if [[ -n "${TIANXIAN_RELEASE_STORE_PASSWORD:-}" ]]; then
  args+=("-PtianxianReleaseStorePassword=$TIANXIAN_RELEASE_STORE_PASSWORD")
fi
if [[ -n "${TIANXIAN_RELEASE_KEY_ALIAS:-}" ]]; then
  args+=("-PtianxianReleaseKeyAlias=$TIANXIAN_RELEASE_KEY_ALIAS")
fi
if [[ -n "${TIANXIAN_RELEASE_KEY_PASSWORD:-}" ]]; then
  args+=("-PtianxianReleaseKeyPassword=$TIANXIAN_RELEASE_KEY_PASSWORD")
fi

./gradlew "${args[@]}"
