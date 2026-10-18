#!/bin/bash
# Server di prova sul computer che ospita l'emulatore Android TV, per le schermate della CI: una
# cartella condivisa Samba (porta 4450, utente "festa") e un server DLNA finto (porta 8200), con tre
# foto demo, una in una sottocartella e un video. L'emulatore li raggiunge all'indirizzo 10.0.2.2.
# Uso, dalla cartella principale, con samba e ffmpeg installati: scripts/android_test_servers.sh
# Le foto stanno in /srv: Samba entra nella cartella come utente "festa", che non legge la home.
set -euo pipefail

SRV=/srv/fotofesta
CONF="$SRV/samba/smb.conf"
# Solo per il Samba di prova, raggiungibile soltanto in loopback.
SMB_PASSWORD="${SMB_PASSWORD:-prova-festa}"
LOGS="${LOGS:-build/screenshots}"
DEMO=app/src/main/res/drawable-nodpi

sudo mkdir -p "$SRV/foto/Compleanno 2024" "$SRV/pubblica" "$SRV"/samba/{run,lock,state,cache,priv,log,ncalrpc}
sudo cp "$DEMO/demo_coast.jpg" "$SRV/foto/Spiaggia.jpg"
sudo cp "$DEMO/demo_picnic.jpg" "$SRV/foto/Picnic.jpg"
sudo cp "$DEMO/demo_mountains.jpg" "$SRV/foto/Montagne.jpg"
sudo cp "$DEMO/demo_birthday.jpg" "$SRV/foto/Compleanno 2024/Torta.jpg"
sudo cp "$DEMO/demo_coast.jpg" "$SRV/pubblica/Spiaggia.jpg"
# Video H.264 con audio AAC: il quadro di prova cambia a ogni fotogramma, così si vede se suona.
VIDEO=$(mktemp --suffix=.mp4)
ffmpeg -y -loglevel error -f lavfi -i testsrc2=size=1280x720:rate=30 -f lavfi -i sine=frequency=440:sample_rate=44100 \
  -t 8 -c:v libx264 -profile:v main -pix_fmt yuv420p -crf 30 -c:a aac -b:a 96k -shortest -movflags +faststart "$VIDEO"
sudo cp "$VIDEO" "$SRV/foto/Video della festa.mp4"
sudo chmod -R a+rX "$SRV/foto" "$SRV/pubblica"

sudo tee "$CONF" >/dev/null <<EOF
[global]
  workgroup = WORKGROUP
  server string = NAS di prova
  smb ports = 4450
  interfaces = 127.0.0.1/8
  bind interfaces only = yes
  disable netbios = yes
  map to guest = Bad User
  server min protocol = SMB2_10
  pid directory = $SRV/samba/run
  lock directory = $SRV/samba/lock
  state directory = $SRV/samba/state
  cache directory = $SRV/samba/cache
  private dir = $SRV/samba/priv
  ncalrpc dir = $SRV/samba/ncalrpc
  log file = $SRV/samba/log/log.%m
[Foto]
  path = $SRV/foto
  read only = yes
  valid users = festa
[Pubblica]
  path = $SRV/pubblica
  read only = yes
  guest ok = yes
EOF
id festa >/dev/null 2>&1 || sudo useradd --no-create-home --shell /usr/sbin/nologin festa
printf '%s\n%s\n' "$SMB_PASSWORD" "$SMB_PASSWORD" | sudo smbpasswd -c "$CONF" -s -a festa >/dev/null
sudo smbd -s "$CONF" -D

mkdir -p "$LOGS"
# Gli indirizzi dei file portano 10.0.2.2, l'indirizzo del computer visto dall'emulatore.
nohup python3 scripts/fake_dlna.py 127.0.0.1 "$SRV/foto" 10.0.2.2 > "$LOGS/fake_dlna.log" 2>&1 &

for port in 4450 8200; do
  for _ in $(seq 40); do
    (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null && break
    sleep 0.5
  done
  if ! (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    echo "::error::Il server di prova sulla porta $port non risponde"
    sudo cat "$SRV"/samba/log/* 2>/dev/null | tail -40 || true
    cat "$LOGS/fake_dlna.log" || true
    exit 1
  fi
done
echo "Server di prova pronti: Samba su 127.0.0.1:4450, DLNA su 127.0.0.1:8200"
ls -la "$SRV/foto"
