# Checklist Google Play per FotoFesta TV

Riepilogo dei requisiti di qualità. I passaggi operativi, nell'ordine in cui eseguirli, sono in [PUBLISHING.md](PUBLISHING.md).

## Qualità tecnica

| Requisito | Stato del progetto | Azione prima del rilascio |
| --- | --- | --- |
| Launcher TV Leanback | Implementato | Verificare su almeno due dispositivi reali |
| Banner Leanback 320×180 nell'app; icona 512×512, grafica 1024×500 e banner TV 1280×720 per la scheda | Implementati | Gli asset della scheda sono in `fastlane/metadata/android/<lingua>/images/` |
| Landscape e overscan | Implementato con margini TV | Testare su pannelli 1080p e 4K |
| Navigazione D-pad | Implementata | Test completo di ogni focus e Back |
| Touchscreen non richiesto | Dichiarato | Nessuna azione |
| `minSdk <= 31` | `minSdk 26` | Nessuna azione |
| Target API TV | `targetSdk 35` | Supera il minimo TV API 34 in vigore nel 2026.[2] |
| Android App Bundle | Generato | Firmare e caricare AAB release |
| 32/64 bit | ARM e x86, 32/64 | Verificare report Play Console |
| 16 KB | `zipalign -P 16` superato dalla 1.0.0 | Ripetere sull'AAB 1.3.0 e leggere il Rapporto pre-lancio |
| Android Lint | Nessun errore nella CI della 1.3.0 | Ripetere con `./scripts/build.sh` o la CI prima di ogni caricamento |
| Test unitari | 113 superati (JVM) | Aggiungere test strumentali su TV reale |

## Scheda Store

Preparare almeno uno screenshot reale ad alta risoluzione della UI TV. Non usare mockup che mostrino funzioni diverse dalla build. La categoria proposta è **Fotografia**; la sezione giochi è secondaria e non richiede che l’app compaia nella riga Giochi, perché il suo scopo principale rimane lo slideshow.

La descrizione deve spiegare che Google Foto richiede selezione esplicita, che USB e le foto degli invitati funzionano senza account, che per le foto degli invitati i telefoni devono essere sulla stessa rete della TV e che la musica è locale. Il salvaschermo è incluso, ma alcune Google TV ne nascondono la scelta: la scheda non deve promettere che sia sempre selezionabile dalle impostazioni. Non promettere sincronizzazione dell’intera libreria o video.

## App access e verifica

Google Play richiede istruzioni di accesso per le funzioni protette da login.[1] Fornire al team di revisione un account di test Google autorizzato nell’OAuth consent screen e una procedura breve per completare i due QR. Fornire anche una modalità demo, già presente, così la UI può essere verificata senza dati personali. Le foto degli invitati si provano senza account, con un telefono sulla stessa rete della TV.

La Google Photos API richiede una verifica OAuth separata.[3]

## Data Safety

La versione corrente non invia dati allo sviluppatore, non contiene annunci e non include più SDK che raccolgono dati (Google Cast è stato rimosso nella 1.3.0): la risposta è "nessun dato raccolto". Le foto degli invitati restano sulla rete di casa e sulla TV. Le risposte da dare sono in [DATA_SAFETY.md](DATA_SAFETY.md). L’informativa pubblica (`site/privacy/index.html`) deve restare coerente con il modulo.

## Accessibilità europea

Mantenere focus evidente, contrasto elevato, testi da distanza, riduzione movimento e piena operabilità da telecomando. Prima della pubblicazione testare TalkBack e Switch Access su hardware reale. Documentare eventuali limiti. L’app non usa intelligenza artificiale, quindi il Regolamento europeo sull’IA non introduce obblighi specifici per le funzioni attuali; questa valutazione va aggiornata se saranno aggiunte descrizioni automatiche o riconoscimento volti.

## Riferimenti

[1]: https://developer.android.com/docs/quality-guidelines/tv-app-quality "Android TV app quality"
[2]: https://support.google.com/googleplay/android-developer/answer/11926878 "Target API level requirements for Google Play apps"
[3]: https://developers.google.com/photos/overview/authorization "Google Photos API authorization scopes"
