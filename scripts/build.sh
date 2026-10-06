#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ -z "${JAVA_HOME:-}" ]; then
  JAVAC_PATH="$(command -v javac || true)"
  if [ -z "$JAVAC_PATH" ]; then
    echo "JDK 17 richiesto: javac non trovato." >&2
    exit 1
  fi
  export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVAC_PATH")")")"
fi
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
./gradlew clean testDebugUnitTest assembleDebug lintDebug bundleRelease
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/FotoFesta-TV-debug.apk
cp app/build/outputs/bundle/release/app-release.aab dist/FotoFesta-TV-release-unsigned.aab
printf 'SHA-256\n' > dist/SHA256SUMS.txt
sha256sum dist/*.apk dist/*.aab >> dist/SHA256SUMS.txt
