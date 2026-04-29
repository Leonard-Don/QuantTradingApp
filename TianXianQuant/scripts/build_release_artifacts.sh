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

"$ROOT_DIR/gradlew" :app:assembleRelease :app:bundleRelease --console=plain

echo "Release APK:"
find "$ROOT_DIR/app/build/outputs/apk/release" -name '*.apk' -maxdepth 1 -print

echo "Release AAB:"
find "$ROOT_DIR/app/build/outputs/bundle/release" -name '*.aab' -maxdepth 1 -print
