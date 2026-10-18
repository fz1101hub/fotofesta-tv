# Scheda Google Play

Testi e grafica per `fastlane supply` (`fastlane android metadata`), una cartella per lingua.

| File | Limite Play | Contenuto |
| --- | --- | --- |
| `title.txt` | 30 caratteri | Nome nella scheda |
| `short_description.txt` | 80 caratteri | Descrizione breve |
| `full_description.txt` | 4000 caratteri | Descrizione completa |
| `changelogs/<versionCode>.txt` | 500 caratteri | Novità della versione |
| `images/icon.png` | 512×512 | Icona |
| `images/featureGraphic.png` | 1024×500 | Grafica in evidenza |
| `images/tvBanner.png` | 1280×720 | Banner Android TV |
| `images/tvScreenshots/` | 1920×1080 | 8 schermate della build reale per lingua (JPEG), dall'emulatore Android TV della CI |

La grafica si rigenera con `python3 scripts/make_store_assets.py`.

Gli screenshot mostrano la build reale: home, slideshow, festa con il QR, slideshow con le foto degli invitati, giochi, gioco con i telefoni, puzzle e mosaico. Si rifanno con `[screenshots]` nel messaggio del commit (o avviando a mano `android.yml`): il job `screenshots` li cattura nell'emulatore Android TV 1080p in ogni lingua, con `scripts/android_screenshots.sh <lingua> store`, e li carica come artefatto `FotoFesta-TV-screenshots-<lingua>-store`. Da una TV collegata si catturano con:

```bash
adb exec-out screencap -p > fastlane/metadata/android/it-IT/images/tvScreenshots/1_home.png
```
