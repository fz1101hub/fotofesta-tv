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
| `images/tvScreenshots/` | 1920×1080 | **Da aggiungere**: almeno uno screenshot reale per lingua |

La grafica si rigenera con `python3 scripts/make_store_assets.py`.

Gli screenshot devono mostrare la build reale. Si catturano dalla TV collegata con adb:

```bash
adb exec-out screencap -p > fastlane/metadata/android/it-IT/images/tvScreenshots/1_home.png
```

Suggeriti: home, slideshow con controlli visibili, impostazioni, collegamento Google Foto con QR, un gioco.
