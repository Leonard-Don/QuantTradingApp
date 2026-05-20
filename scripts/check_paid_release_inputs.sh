#!/usr/bin/env bash
# Reject placeholder/example values for paid release inputs before Gradle runs.
# The Gradle gate (app/build.gradle.kts: verifyPaidReleaseConfig) only checks
# emptiness and a couple of known-bad URLs. The README and signing docs include
# example.com / support@example.com sample values; pasting those literally
# would currently slip past the Gradle gate. This precheck blocks placeholder
# hosts and obviously-malformed inputs before the build starts.
set -euo pipefail

errors=()

is_https_url() {
  [[ "$1" =~ ^https://[^[:space:]]+$ ]]
}

contains_placeholder_host() {
  local lower
  lower="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "$lower" in
    *example.com*|*example.org*|*example.net*) return 0 ;;
    *your-domain*|*your_domain*|*replace-me*|*replace_me*|*todo.local*) return 0 ;;
    *localhost*|*127.0.0.1*|*0.0.0.0*|*10.0.2.2*) return 0 ;;
  esac
  return 1
}

# Does the URL carry a ``user[:password]@`` userinfo component in its
# authority section? verify_paid_release_config.sh passes the URL to Gradle
# on the command line, which is echoed into CI logs and baked into
# BuildConfig - any embedded credentials would leak.
url_has_userinfo() {
  local rest="${1#https://}"
  local authority="${rest%%/*}"
  authority="${authority%%\?*}"
  authority="${authority%%#*}"
  [[ "$authority" == *"@"* ]]
}

check_https_url() {
  local name="$1" value="${2:-}"
  if [[ -z "$value" ]]; then
    return 0
  fi
  if ! is_https_url "$value"; then
    errors+=("$name must be an https:// URL")
    return
  fi
  if contains_placeholder_host "$value"; then
    errors+=("$name uses a placeholder/local host")
  fi
  if url_has_userinfo "$value"; then
    errors+=("$name must not include embedded userinfo credentials")
  fi
}

check_email() {
  local name="$1" value="${2:-}"
  if [[ -z "$value" ]]; then
    return 0
  fi
  if [[ ! "$value" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
    errors+=("$name is not a valid email")
    return
  fi
  if contains_placeholder_host "$value"; then
    errors+=("$name uses a placeholder domain")
  fi
}

check_https_url QUANTTRADING_PRODUCTION_API_BASE_URL "${QUANTTRADING_PRODUCTION_API_BASE_URL:-}"
check_https_url QUANTTRADING_PRIVACY_POLICY_URL      "${QUANTTRADING_PRIVACY_POLICY_URL:-}"
check_https_url QUANTTRADING_TERMS_URL               "${QUANTTRADING_TERMS_URL:-}"
check_https_url QUANTTRADING_DATA_DISCLAIMER_URL     "${QUANTTRADING_DATA_DISCLAIMER_URL:-}"
check_email     QUANTTRADING_SUPPORT_EMAIL           "${QUANTTRADING_SUPPORT_EMAIL:-}"

if [[ -n "${QUANTTRADING_RELEASE_KEYSTORE:-}" && ! -f "$QUANTTRADING_RELEASE_KEYSTORE" ]]; then
  errors+=("QUANTTRADING_RELEASE_KEYSTORE points to a missing file")
fi

if (( ${#errors[@]} > 0 )); then
  {
    echo "Paid release inputs failed precheck:"
    for e in "${errors[@]}"; do
      echo " - $e"
    done
    echo
    echo "Replace placeholder/example values with real production resources before running the Gradle gate."
  } >&2
  exit 1
fi

echo "Paid release input precheck passed."
