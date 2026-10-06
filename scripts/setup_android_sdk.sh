#!/usr/bin/env bash
set -euo pipefail
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
TOOLS_ZIP="/tmp/commandlinetools-linux.zip"
TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
mkdir -p "$SDK_ROOT/cmdline-tools"
if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fL "$TOOLS_URL" -o "$TOOLS_ZIP"
  rm -rf /tmp/android-commandlinetools "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p /tmp/android-commandlinetools
  unzip -q "$TOOLS_ZIP" -d /tmp/android-commandlinetools
  mv /tmp/android-commandlinetools/cmdline-tools "$SDK_ROOT/cmdline-tools/latest"
fi
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" \
  "platforms;android-35" \
  "platforms;android-36" \
  "build-tools;35.0.0" \
  "build-tools;36.0.0"
printf 'sdk.dir=%s\n' "$SDK_ROOT" > "$(cd "$(dirname "$0")/.." && pwd)/local.properties"
echo "Android SDK pronto in $SDK_ROOT"
