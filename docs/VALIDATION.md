# Rapporto di validazione

**Data:** 7 settembre 2026  
**Versione app:** 1.0.0 (`versionCode 1`)

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

I test unitari verificano i comandi Cast play/next, il parsing e i limiti delle impostazioni, l’accettazione esclusiva di URL HTTPS pubblici, il rifiuto di host locali o credenziali incorporate e il rifiuto di versioni protocollo non supportate.

## Artefatti e checksum

```text
bc337d7715dcbec3810aef4266b2d971ce359e9f17ca1e7e655259b535f4539a  FotoFesta-TV-debug.apk
645d678d48abeafda59f685e34ef5bae7927d3fe2c0e8212841825e521e107e4  FotoFesta-TV-release-unsigned.aab
```

Il checksum dell’archivio sorgente è riportato nel file `dist/SHA256SUMS.txt`, perché cambia quando questo rapporto viene incluso nell’archivio.

## Test ancora necessari prima della pubblicazione

La sandbox non dispone di un account OAuth Photos API del titolare, di una Cast Application ID registrata né di un televisore fisico. Pertanto, prima di pubblicare, occorre completare una prova end-to-end su almeno due dispositivi Google TV/Android TV reali: login QR, selezione Picker, rete intermittente, hard disk USB reale, rimozione USB durante lo slideshow, audio, standby/ripresa, sender Cast, TalkBack e focus D-pad. Occorre inoltre caricare l’AAB su una traccia di test Play Console ed eseguire il Pre-launch Report.

Il file AAB consegnato è intenzionalmente **non firmato con una chiave di produzione**. Deve essere firmato/configurato con la upload key del proprietario oppure gestito con Play App Signing.
