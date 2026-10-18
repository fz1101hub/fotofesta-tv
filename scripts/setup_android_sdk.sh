#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
mkdir -p "$SDK_ROOT/cmdline-tools"
if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  WORK_DIR="$(mktemp -d)"
  trap 'rm -rf "$WORK_DIR"' EXIT
  curl -fL "$TOOLS_URL" -o "$WORK_DIR/commandlinetools-linux.zip"
  unzip -q "$WORK_DIR/commandlinetools-linux.zip" -d "$WORK_DIR"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$WORK_DIR/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0"

# Aggiorna solo sdk.dir: le altre chiavi (credenziali OAuth, firma release) restano intatte.
PROPS="$ROOT/local.properties"
touch "$PROPS"
grep -v '^sdk\.dir=' "$PROPS" > "$PROPS.tmp" || true
{ printf 'sdk.dir=%s\n' "$SDK_ROOT"; cat "$PROPS.tmp"; } > "$PROPS"
rm -f "$PROPS.tmp"
echo "Android SDK pronto in $SDK_ROOT"
