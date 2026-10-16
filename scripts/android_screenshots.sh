#!/bin/bash
# Schermate dell'app nell'emulatore Android TV, una per scenario (vedi
# app/src/debug/java/it/fotofesta/tv/ScreenshotScenario.kt).
# Uso, dalla cartella principale, con l'emulatore acceso e l'APK di debug compilato:
#   scripts/android_screenshots.sh <lingua> <store|checks>
# "store": le schermate per Google Play a piena risoluzione (JPEG 1920x1080). "checks": tutte le
# schermate, ridotte, comprese cartelle condivise, cartelle di rete e video dai server di
# scripts/android_test_servers.sh. Le PNG finiscono in build/screenshots/<lingua>-<insieme>; alla fine il
# log riporta ogni schermata in JPEG base64, fra "===SHOT nome===" e "===END===". Il passo fallisce se
# l'app si chiude da sola o se uno scenario non arriva alla sua schermata.
set -euo pipefail

LANGUAGE="${1:-it}"
SET="${2:-checks}"
case "$LANGUAGE" in
  it) LOCALE=it-IT ;; fr) LOCALE=fr-FR ;; es) LOCALE=es-ES ;; de) LOCALE=de-DE ;; *) LOCALE=en-US ;;
esac
PACKAGE=it.fotofesta.tv
APK=app/build/outputs/apk/debug/app-debug.apk
OUT="build/screenshots/$LANGUAGE-$SET"
# Il computer che ospita l'emulatore, visto dall'emulatore, e l'accesso al Samba di prova.
HOST=10.0.2.2
SMB_PASSWORD="${SMB_PASSWORD:-prova-festa}"
SHOTS=()
FAILED=0
mkdir -p "$OUT"

adb wait-for-device
adb shell wm size
adb install -r -g "$APK"
# Lingua dell'app (Android 13+): l'app la dichiara in res/xml/locales_config.xml.
adb shell cmd locale set-app-locales "$PACKAGE" --locales "$LOCALE"
adb shell cmd locale get-app-locales "$PACKAGE" || true
# Niente salvaschermo né standby durante le schermate.
adb shell settings put secure screensaver_enabled 0 || true
adb shell svc power stayon true || true
adb logcat -c || true

status() { adb shell run-as "$PACKAGE" cat cache/screenshot-status.txt 2>/dev/null | tr -d '\r' || true; }

alive() { adb shell pidof "$PACKAGE" >/dev/null 2>&1; }

launch() {
  adb shell run-as "$PACKAGE" rm -f cache/screenshot-status.txt >/dev/null 2>&1 || true
  adb shell am start -W -S -n "$PACKAGE/.MainActivity" \
    --es fotofesta.screenshot "$1" \
    --es fotofesta.smb "$HOST:4450" --es fotofesta.smb_user festa --es fotofesta.smb_password "$SMB_PASSWORD" \
    --es fotofesta.dlna "http://$HOST:8200/rootDesc.xml" >/dev/null
}

# Schermata quando lo scenario è pronto, dopo qualche secondo per le immagini.
capture() {
  local scenario=$1 settle=$2 timeout=$3 state=""
  for _ in $(seq $((timeout * 2))); do
    state=$(status)
    [[ "$state" == ready* || "$state" == error* ]] && break
    alive || break
    sleep 0.5
  done
  sleep "$settle"
  if ! alive; then
    echo "::error::L'app si è chiusa nello scenario $scenario"
    adb logcat -d -b crash | tail -80 || true
    FAILED=1
    return
  fi
  case "$state" in
    ready*) echo "$scenario: pronto" ;;
    error*) echo "::error::Scenario $scenario: ${state#error: }"; FAILED=1 ;;
    *) echo "::error::Scenario $scenario non pronto dopo ${timeout}s (stato: ${state:-nessuno})"; FAILED=1 ;;
  esac
  adb exec-out screencap -p > "$OUT/$scenario.png"
  SHOTS+=("$scenario")
}

# shoot <scenario> [secondi di attesa dopo "ready"] [secondi massimi per arrivarci]
shoot() {
  launch "$1"
  capture "$1" "${2:-3}" "${3:-90}"
}

if [ "$SET" = store ]; then
  shoot home 4
  shoot slideshow 5
  shoot party 4
  shoot guest_slideshow 3
  shoot games 3
  shoot who_phone 3
  shoot puzzle_game 4
  shoot browser 3
  shoot mosaic 3
else
  shoot home 4
  shoot slideshow 5
  shoot settings 2
  shoot settings_party 2
  shoot games 2
  shoot memory 3
  shoot mystery 3
  shoot puzzle 2
  shoot puzzle_game 4
  shoot guests 2
  shoot party 4
  shoot guest_slideshow 3
  shoot who_game 3
  shoot who_phone 3
  shoot awards 3
  shoot review 3
  shoot browser 3
  shoot icloud 3
  shoot smb_login 2
  shoot smb_error 2
  shoot smb_shares 2
  shoot smb_folder 2
  shoot smb_slideshow 3 120
  shoot smb_video 1 150
  # La cartella condivisa in uso compare nel browser, pronta da riaprire.
  shoot browser_saved 3
  shoot dlna 2
  shoot dlna_folder 2
  shoot dlna_slideshow 3 120
  shoot dlna_video 1 150
  # Per ultimi: cambiano le impostazioni salvate dello slideshow.
  shoot mosaic 3
  shoot countdown 3
fi
adb shell am force-stop "$PACKAGE" || true

echo "--- Log dell'app (avvisi ed errori) ---"
adb logcat -d '*:W' | grep -E "FotoFesta|AndroidRuntime|ExoPlayer|MediaCodec" | tail -150 || true

for shot in "${SHOTS[@]}"; do
  if [ "$SET" = store ]; then
    ffmpeg -y -loglevel error -i "$OUT/$shot.png" -q:v 3 "$OUT/$shot.jpg"
  else
    ffmpeg -y -loglevel error -i "$OUT/$shot.png" -vf scale=1280:-2 -q:v 5 "$OUT/$shot.jpg"
  fi
  echo "===SHOT $shot==="
  base64 -w 0 "$OUT/$shot.jpg" | fold -w 8000
  echo
  echo "===END==="
done

exit "$FAILED"
