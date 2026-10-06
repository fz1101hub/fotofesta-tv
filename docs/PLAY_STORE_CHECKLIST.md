# Checklist Google Play per FotoFesta TV

## Qualità tecnica

| Requisito | Stato del progetto | Azione prima del rilascio |
| --- | --- | --- |
| Launcher TV Leanback | Implementato | Verificare su almeno due dispositivi reali |
| Banner 320×180 e icona 512×512 | Implementati | Usare gli stessi asset nella scheda Play |
| Landscape e overscan | Implementato con margini TV | Testare su pannelli 1080p e 4K |
| Navigazione D-pad | Implementata | Test completo di ogni focus e Back |
| Touchscreen non richiesto | Dichiarato | Nessuna azione |
| `minSdk <= 31` | `minSdk 26` | Nessuna azione |
| Target API TV | `targetSdk 35` | Supera il minimo TV API 34 in vigore nel 2026.[2] |
| Android App Bundle | Generato | Firmare e caricare AAB release |
| 32/64 bit | ARM e x86, 32/64 | Verificare report Play Console |
| 16 KB | `zipalign -P 16` superato | Eseguire anche Pre-launch Report |
| Android Lint | 0 problemi | Mantenere in CI |
| Test protocollo Cast | 5/5 | Aggiungere test strumentali su TV reale |

## Scheda Store

Preparare almeno uno screenshot reale ad alta risoluzione della UI TV. Non usare mockup che mostrino funzioni diverse dalla build. La categoria proposta è **Fotografia**; la sezione giochi è secondaria e non richiede che l’app compaia nella riga Giochi, perché il suo scopo principale rimane lo slideshow.

La descrizione deve spiegare che Google Foto richiede selezione esplicita, che USB funziona senza account e che la musica è locale. Non promettere sincronizzazione dell’intera libreria, screensaver di sistema o video finché tali funzioni non sono incluse.

## App access e verifica

Google Play richiede istruzioni di accesso per le funzioni protette da login.[1] Fornire al team di revisione un account di test Google autorizzato nell’OAuth consent screen e una procedura breve per completare i due QR. Fornire anche una modalità demo, già presente, così la UI può essere verificata senza dati personali.

La Google Photos API richiede una verifica OAuth separata.[3] La Cast Console richiede registrazione, associazione del package e pubblicazione del Receiver Application ID.[4]

## Data Safety

La versione corrente non invia dati al server dello sviluppatore e non contiene ads o analytics. Tuttavia, il modulo Data Safety deve riflettere l’esatto comportamento della build pubblicata e l’uso delle API Google. Verificare in particolare foto/video, token, diagnostica, eventuale assistenza e qualunque SDK aggiunto dopo questa consegna. L’informativa pubblica deve essere coerente con il modulo.

## Accessibilità europea

Mantenere focus evidente, contrasto elevato, testi da distanza, riduzione movimento e piena operabilità da telecomando. Prima della pubblicazione testare TalkBack e Switch Access su hardware reale. Documentare eventuali limiti. L’app non usa intelligenza artificiale, quindi il Regolamento europeo sull’IA non introduce obblighi specifici per le funzioni attuali; questa valutazione va aggiornata se saranno aggiunte descrizioni automatiche o riconoscimento volti.

## Riferimenti

[1]: https://developer.android.com/docs/quality-guidelines/tv-app-quality "Android TV app quality"
[2]: https://support.google.com/googleplay/android-developer/answer/11926878 "Target API level requirements for Google Play apps"
[3]: https://developers.google.com/photos/overview/authorization "Google Photos API authorization scopes"
[4]: https://developers.google.com/cast/docs/registration "Google Cast application registration"
