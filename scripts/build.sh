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
# Google Play (AAB) e Amazon Appstore per Fire TV (APK).
./gradlew clean testPlayDebugUnitTest assemblePlayDebug assembleAmazonDebug lintPlayDebug lintAmazonDebug bundlePlayRelease assembleAmazonRelease
rm -rf dist
mkdir -p dist
cp app/build/outputs/apk/play/debug/app-play-debug.apk dist/FotoFesta-TV-debug.apk
cp app/build/outputs/apk/amazon/debug/app-amazon-debug.apk dist/FotoFesta-TV-FireTV-debug.apk
# L'AAB è firmato solo se RELEASE_STORE_FILE è configurato (vedi docs/CONFIGURATION.md).
if grep -qs '^RELEASE_STORE_FILE=' local.properties || [ -n "${RELEASE_STORE_FILE:-}" ]; then
  cp app/build/outputs/bundle/playRelease/app-play-release.aab dist/FotoFesta-TV-release.aab
  cp app/build/outputs/apk/amazon/release/app-amazon-release.apk dist/FotoFesta-TV-FireTV-release.apk
else
  cp app/build/outputs/bundle/playRelease/app-play-release.aab dist/FotoFesta-TV-release-unsigned.aab
  cp app/build/outputs/apk/amazon/release/app-amazon-release-unsigned.apk dist/FotoFesta-TV-FireTV-release-unsigned.apk
fi
printf 'SHA-256\n' > dist/SHA256SUMS.txt
sha256sum dist/*.apk dist/*.aab >> dist/SHA256SUMS.txt
