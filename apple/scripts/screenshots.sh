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
SCENARIOS=(home slideshow settings games memory mystery puzzle puzzle_game guests party party_slideshow)
SHOTS=()

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
URL_FILE="$(xcrun simctl get_app_container "$DEVICE" "$BUNDLE_ID" data)/Library/Caches/screenshot-guest-url.txt"

launch() {
  xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true
  rm -f "$URL_FILE"
  SIMCTL_CHILD_FOTOFESTA_SCREENSHOT="$1" xcrun simctl launch "$DEVICE" "$BUNDLE_ID" \
    -AppleLanguages "($LANGUAGE)" -AppleLocale "$LOCALE"
}

# Schermata dopo un'attesa. Se l'app si è chiusa da sola, è un crash: il passo fallisce. L'elenco si
# legge tutto prima di cercarlo: con pipefail, grep -q che smette di leggere farebbe fallire
# launchctl (SIGPIPE).
capture() {
  sleep "$2"
  running=$(xcrun simctl spawn "$DEVICE" launchctl list)
  if ! grep -q "UIKitApplication:$BUNDLE_ID" <<<"$running"; then
    echo "::error::L'app si è chiusa nello scenario $1"
    exit 1
  fi
  xcrun simctl io "$DEVICE" screenshot "$OUT/$1.png"
  SHOTS+=("$1")
}

# Manda una foto come la pagina per i telefoni: POST /photos con la chiave dell'indirizzo del QR.
upload() {
  for _ in $(seq 60); do [ -s "$URL_FILE" ] && break; sleep 0.5; done
  if [ ! -s "$URL_FILE" ]; then
    echo "::error::La festa non ha un indirizzo per gli invitati"
    exit 1
  fi
  url=$(cat "$URL_FILE")
  curl -fsS --max-time 30 -X POST \
    -H "X-FotoFesta-Key: $(sed 's|.*?k=||' <<<"$url")" \
    -H "X-FotoFesta-Sender: $2" \
    -H "Content-Type: image/jpeg" \
    --data-binary "@$1" "$(sed 's|/?k=.*||' <<<"$url")/photos"
  echo
}

# Risponde dal telefono di due invitati, come la pagina della festa: GET /game e POST /game/answer.
answer_phones() {
  local url key base round=""
  url=$(cat "$URL_FILE")
  key=$(sed 's|.*?k=||' <<<"$url")
  base=$(sed 's|/?k=.*||' <<<"$url")
  for _ in $(seq 60); do
    round=$(curl -fsS --max-time 5 -H "X-FotoFesta-Key: $key" "$base/game" \
      | python3 -c 'import json, sys; print(json.load(sys.stdin).get("round") or "")' || true)
    [ -n "$round" ] && break
    sleep 0.5
  done
  if [ -z "$round" ]; then
    echo "::error::Nessuna domanda per i telefoni"
    exit 1
  fi
  curl -fsS --max-time 10 -H "X-FotoFesta-Key: $key" \
    --data "round=$round&choice=0&player=telefono-giulia&name=Giulia" "$base/game/answer"
  echo
  curl -fsS --max-time 10 -H "X-FotoFesta-Key: $key" \
    --data "round=$round&choice=1&player=telefono-marco&name=Marco" "$base/game/answer"
  echo
}

for scenario in "${SCENARIOS[@]}"; do
  launch "$scenario"
  capture "$scenario" 10
done

# Foto degli invitati: una verticale, come quelle dei telefoni, e una orizzontale.
sips -c 1440 1080 FotoFestaTV/Resources/Demo/demo_birthday.jpg --out "$OUT/guest_portrait.jpg" >/dev/null
sips -Z 1600 FotoFestaTV/Resources/Demo/demo_mountains.jpg --out "$OUT/guest_landscape.jpg" >/dev/null
launch party_upload
upload "$OUT/guest_portrait.jpg" Giulia
sleep 1
upload "$OUT/guest_landscape.jpg" Marco
capture guest_slideshow 4
capture party_photos 12
launch who_game
upload "$OUT/guest_portrait.jpg" Giulia
upload "$OUT/guest_landscape.jpg" Marco
capture who_game 6
launch who_phone
upload "$OUT/guest_portrait.jpg" Giulia
upload "$OUT/guest_landscape.jpg" Marco
answer_phones
capture who_phone 6
launch awards
upload "$OUT/guest_portrait.jpg" Giulia
upload "$OUT/guest_landscape.jpg" Marco
upload "$OUT/guest_portrait.jpg" Giulia
capture awards 6
# Con l'approvazione accesa la foto aspetta in "Da approvare".
launch review
upload "$OUT/guest_portrait.jpg" Sara
capture review 6
# Cartelle di rete: un server DLNA finto sull'indirizzo del Mac, che il simulatore raggiunge come un
# NAS della rete di casa. Si aggiunge con l'indirizzo, come senza l'entitlement multicast.
launch network
capture network 8
HOST_IP=$(ipconfig getifaddr en0 || ipconfig getifaddr en1 || true)
if [[ "$HOST_IP" =~ ^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.) ]]; then
  python3 scripts/fake_dlna.py "$HOST_IP" FotoFestaTV/Resources/Demo > "$OUT/fake_dlna.log" 2>&1 &
  DLNA_PID=$!
  sleep 1
  SIMCTL_CHILD_FOTOFESTA_NETWORK_HOST="$HOST_IP" launch network_folder
  capture network_folder 14
  echo "Indirizzo del Mac: $HOST_IP"
  cat "$(dirname "$URL_FILE")/screenshot-network.txt" || echo "::warning::Nessuna diagnosi delle cartelle di rete"
  SIMCTL_CHILD_FOTOFESTA_NETWORK_HOST="$HOST_IP" launch network_slideshow
  capture network_slideshow 20
  kill "$DLNA_PID" 2>/dev/null || true
  cat "$OUT/fake_dlna.log"
else
  echo "::warning::Indirizzo del Mac non privato ($HOST_IP): schermate delle cartelle di rete saltate"
fi
# Libreria Foto: permesso concesso e foto demo aggiunte alla libreria del simulatore, se lo consente.
xcrun simctl privacy "$DEVICE" grant photos "$BUNDLE_ID" || echo "::warning::Permesso delle foto non concesso"
xcrun simctl addmedia "$DEVICE" FotoFestaTV/Resources/Demo/*.jpg || echo "::warning::Foto non aggiunte alla libreria"
launch library
capture library 8
launch library_slideshow
capture library_slideshow 14
# Mosaico per ultimo: cambia l'impostazione salvata. Dopo due cambi di foto le caselle sono piene.
launch mosaic
capture mosaic 20
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true

for shot in "${SHOTS[@]}"; do
  sips -Z 1280 -s format jpeg -s formatOptions 80 "$OUT/$shot.png" --out "$OUT/$shot.jpg" >/dev/null
  echo "===SHOT $shot==="
  base64 -i "$OUT/$shot.jpg" | tr -d '\n' | fold -w 8000
  echo
  echo "===END==="
done
