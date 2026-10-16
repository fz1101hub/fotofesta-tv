#!/bin/bash
# Schermate dell'app nel simulatore Apple TV, una per scenario (vedi ScreenshotScenario.swift).
# Uso, dalla cartella apple dopo `xcodegen generate`: scripts/screenshots.sh [lingua]
# Le PNG a piena risoluzione finiscono in build/screenshots; alla fine il log riporta anche una
# versione ridotta di ogni schermata in base64, fra "===SHOT nome===" e "===END===".
set -euo pipefail

LANGUAGE="${1:-it}"
case "$LANGUAGE" in
  it) LOCALE=it_IT ;; fr) LOCALE=fr_FR ;; es) LOCALE=es_ES ;; de) LOCALE=de_DE ;; *) LOCALE=en_US ;;
esac
OUT="build/screenshots"
BUNDLE_ID="it.fotofesta.tv"
# La festa resta accesa fra un avvio e l'altro: gli scenari della festa vanno per ultimi.
SCENARIOS=(home slideshow settings games memory mystery guests party party_slideshow)

# Simulatore Apple TV con il runtime tvOS più recente, preferendo il modello 4K.
pick_device() {
  xcrun simctl list devices available -j | python3 -c '
import json, re, sys
best = None
for runtime, items in json.load(sys.stdin)["devices"].items():
    if "tvOS" not in runtime:
        continue
    version = [int(n) for n in re.findall(r"\d+", runtime.split("tvOS")[-1])]
    for device in items:
        key = (version, "4K" in device["name"] and "1080p" not in device["name"])
        if best is None or key > best[0]:
            best = (key, device["udid"])
print(best[1] if best else "")
'
}

pick_runtime() {
  xcrun simctl list runtimes available -j | python3 -c '
import json, sys
runtimes = [r for r in json.load(sys.stdin)["runtimes"] if "tvOS" in r.get("platform", r.get("name", ""))]
runtimes.sort(key=lambda r: [int(n) for n in r["version"].split(".")])
print(runtimes[-1]["identifier"] if runtimes else "")
'
}

DEVICE=$(pick_device)
if [ -z "$DEVICE" ]; then
  RUNTIME=$(pick_runtime)
  if [ -z "$RUNTIME" ]; then
    xcodebuild -downloadPlatform tvOS
    RUNTIME=$(pick_runtime)
  fi
  DEVICE=$(xcrun simctl create "Apple TV 4K" com.apple.CoreSimulator.SimDeviceType.Apple-TV-4K-3rd-generation-4K "$RUNTIME")
fi
echo "Simulatore: $DEVICE"
xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b

xcodebuild build \
  -project FotoFestaTV.xcodeproj \
  -scheme FotoFestaTV \
  -configuration Debug \
  -destination "platform=tvOS Simulator,id=$DEVICE" \
  -derivedDataPath build/sim \
  CODE_SIGNING_ALLOWED=NO \
  -quiet
xcrun simctl install "$DEVICE" "build/sim/Build/Products/Debug-appletvsimulator/FotoFesta TV.app"

mkdir -p "$OUT"
for scenario in "${SCENARIOS[@]}"; do
  xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true
  SIMCTL_CHILD_FOTOFESTA_SCREENSHOT="$scenario" xcrun simctl launch "$DEVICE" "$BUNDLE_ID" \
    -AppleLanguages "($LANGUAGE)" -AppleLocale "$LOCALE"
  sleep 10
  # Se l'app si è chiusa da sola, è un crash: il passo fallisce. L'elenco si legge tutto prima di
  # cercarlo: con pipefail, grep -q che smette di leggere farebbe fallire launchctl (SIGPIPE).
  running=$(xcrun simctl spawn "$DEVICE" launchctl list)
  if ! grep -q "UIKitApplication:$BUNDLE_ID" <<<"$running"; then
    echo "::error::L'app si è chiusa nello scenario $scenario"
    exit 1
  fi
  xcrun simctl io "$DEVICE" screenshot "$OUT/$scenario.png"
done
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true

for scenario in "${SCENARIOS[@]}"; do
  sips -Z 1280 -s format jpeg -s formatOptions 80 "$OUT/$scenario.png" --out "$OUT/$scenario.jpg" >/dev/null
  echo "===SHOT $scenario==="
  base64 -i "$OUT/$scenario.jpg" | tr -d '\n' | fold -w 8000
  echo
  echo "===END==="
done
