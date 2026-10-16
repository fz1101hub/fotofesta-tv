# Rapporto di validazione

**Data:** 7 settembre 2026  
**Versione app:** 1.0.0 (`versionCode 1`)

> Questo rapporto descrive la build 1.0.0. Le correzioni successive all’audit del 6 ottobre 2026 e le versioni 1.2.0 e 1.3.0 sono documentate in [AUDIT.md](AUDIT.md) e verificate dalla CI GitHub Actions. Dalla 1.3.0 Google Cast è stato rimosso e sostituito dalle foto degli invitati.

## Risultati

| Controllo | Comando / evidenza | Risultato |
| --- | --- | --- |
| Build debug | `./gradlew assembleDebug` | Superata |
| Build bundle release | `./gradlew bundleRelease` con R8 | Superata |
| Test unitari | `./gradlew testDebugUnitTest` | 5 test, 0 failure, 0 error |
| Android Lint | `./gradlew lintDebug` | Nessun problema rilevato |
| Package | `aapt dump badging` | `it.fotofesta.tv`, `minSdk 26`, `targetSdk 35` |
| Launcher TV | `aapt dump badging` | Activity Leanback, icona e banner presenti |
| Firma APK | `apksigner verify --verbose` | Firma debug valida, APK Signature Scheme v2 |
| ABI native | Elenco `lib/` nell’APK | ARMv7, ARM64, x86 e x86_64 |
| Allineamento | `zipalign -c -P 16 4` | Superato |
| Traffico in chiaro | Manifest e Network Security Config | Disabilitato |
| Segreti repository | `.gitignore` e archivio sorgente | `local.properties`, keystore e build esclusi |

Nella 1.0.0 i test unitari verificavano il protocollo Cast, poi rimosso. I test attuali sono descritti nel [README](../README.md#verifica-effettuata).

## Emulatore Android TV (1.4.0)

Con `[screenshots]` nel messaggio del commit, o con l'avvio a mano di `android.yml`, la CI accende l'emulatore Android TV 14 a 1080p e apre l'APK di debug in 32 schermate (`scripts/android_screenshots.sh`, scenari in `app/src/debug/.../ScreenshotScenario.kt`). Sul runner girano un Samba 4 e un server DLNA di prova con tre foto, una sottocartella e un video H.264 con audio (`scripts/android_test_servers.sh`), raggiunti dall'emulatore come un NAS della rete di casa. Il passo fallisce se l'app si chiude da sola o se una schermata non si apre.

| Flusso | Risultato nell'emulatore |
| --- | --- |
| Home, slideshow, impostazioni, giochi, puzzle, mosaico, conto alla rovescia | Schermate corrette in italiano, inglese, francese, spagnolo e tedesco |
| Festa | Foto mandate alla pagina della TV come dai telefoni, slideshow con QR, approvazione, premi |
| Gioco con i telefoni | Due telefoni rispondono dalla pagina della festa; risposta giusta e classifica |
| Cartelle condivise (SMB) | Accesso con password, password sbagliata con il suo messaggio, condivisioni, sottocartelle, slideshow, video riprodotto, cartella in uso nel browser; tastiera chiusa all'apertura e aperta premendo OK sul campo |
| Cartelle di rete (DLNA) | Server aggiunto, cartelle, slideshow, video riprodotto |
| Album iCloud | Schermata con il QR (il servizio di Apple non è raggiungibile dalla CI) |

Problemi trovati così e corretti sono in [AUDIT.md](AUDIT.md#emulatore-android-tv-140). Restano da provare su TV reali la navigazione completa con il telecomando (la CI apre le schermate da sola e preme solo OK su un campo), le tastiere dei produttori e la ricerca automatica in rete (l'emulatore non riceve il multicast).

## Artefatti e checksum

```text
bc337d7715dcbec3810aef4266b2d971ce359e9f17ca1e7e655259b535f4539a  FotoFesta-TV-debug.apk
645d678d48abeafda59f685e34ef5bae7927d3fe2c0e8212841825e521e107e4  FotoFesta-TV-release-unsigned.aab
```

Il checksum dell’archivio sorgente è riportato nel file `dist/SHA256SUMS.txt`, perché cambia quando questo rapporto viene incluso nell’archivio.

## Test ancora necessari prima della pubblicazione

La sandbox non dispone di un account OAuth Photos API del titolare né di un televisore fisico. Pertanto, prima di pubblicare, occorre completare una prova end-to-end su almeno due dispositivi Google TV/Android TV reali: login QR, selezione Picker, rete intermittente, hard disk USB reale scelto con il browser delle cartelle (permesso concesso e negato, TV con e senza selettore di Android), rimozione USB durante lo slideshow, scelta del brano, audio, standby/ripresa, foto degli invitati da telefoni Android e iPhone sulla stessa Wi‑Fi (approvazione, QR della Wi‑Fi, uscita e ritorno nell’app, fine festa), TalkBack e focus D-pad. Dalla 1.4.0 vanno provati anche: cartelle di rete con almeno due server reali (un NAS e Plex o Jellyfin; server spento, cambio di indirizzo IP), video da USB, rete e Google Foto in vari formati (MP4 H.264 e HEVC, MKV, MOV, un formato che la TV non decodifica), audio dei video con la musica di sottofondo, gioco con i telefoni con più invitati e premi di fine festa. Su Apple TV i video di Google Foto (lunghi, con avanzamento e pausa) e il gioco con i telefoni. Per le nuove sorgenti: cartelle condivise SMB con un PC Windows 11 (account locale e account Microsoft), un Mac e un NAS (Synology o QNAP, anche come ospite e dopo lo standby), con molte foto e un video lungo; album condiviso di iCloud con il link pubblico, dal QR e dal codice, con foto aggiunte dall’iPhone durante lo slideshow e con il link poi disattivato (il servizio di Apple non era raggiungibile dall’ambiente di sviluppo: è stato scritto sul comportamento documentato da progetti open source e va provato con un album reale); su Apple TV Foto di iCloud (permesso concesso e negato, album condiviso con foto aggiunte da un iPhone, video) e cartelle di rete aggiunte con l’indirizzo IP. Occorre inoltre caricare l’AAB su una traccia di test Play Console ed eseguire il Pre-launch Report.

Il file AAB consegnato è intenzionalmente **non firmato con una chiave di produzione**. Deve essere firmato/configurato con la upload key del proprietario oppure gestito con Play App Signing.
