# FotoFesta TV

**FotoFesta TV** è un’app Android nativa per Android TV e Google TV che mostra fotografie in slideshow direttamente da una cartella USB oppure da una selezione sicura in Google Foto. Include durata regolabile, tre transizioni, adattamento immagine, ordine casuale, musica locale, controlli D-pad, Google Cast Connect e due minigiochi basati sulle fotografie attive.

> **Stato del progetto:** MVP funzionale. L’APK debug (generato da `scripts/build.sh` o scaricabile dagli artefatti della CI GitHub) funziona subito con foto demo, USB, musica e giochi. Per Google Foto occorre inserire un proprio client OAuth; per ricevere sessioni Cast occorre registrare il ricevitore nella Google Cast SDK Developer Console.

![Architettura di FotoFesta TV](docs/architecture.png)

## Funzionalità implementate

| Area | Funzione | Stato |
| --- | --- | --- |
| Sorgenti | Foto demo incluse | Pronta |
| Sorgenti | Cartella su hard disk USB tramite Storage Access Framework | Pronta; nessuna copia delle foto |
| Sorgenti | Google Photos Picker API con autorizzazione TV e QR | Pronta nel codice; richiede credenziali OAuth del proprietario |
| Slideshow | Durata 3, 5, 8, 12 o 20 secondi | Pronta |
| Slideshow | Dissolvenza, scorrimento e zoom dolce | Pronta |
| Slideshow | Foto intera o riempimento schermo, shuffle, orologio | Pronta |
| Accessibilità | Focus visibile, D-pad, tasti media, riduzione movimento, contrasto elevato | Pronta |
| Audio | Brano locale selezionato dall’utente, loop e volume | Pronta |
| Chromecast | Ricevitore Cast Connect, `MediaSession` e protocollo JSON v1 | Pronta nel codice; registrazione Cast richiesta |
| Giochi | Memory dei ricordi | Pronta |
| Giochi | Foto misteriosa | Pronta |
| Privacy | Nessuna pubblicità o analytics; token AES-GCM in Android Keystore | Pronta |
| Google Play | Banner 320×180, icona 512×512, Leanback launcher, AAB | Pronta |

## Scelta dell’approccio

| Approach | Tradeoffs | Cost | Setup Complexity |
| --- | --- | --- | --- |
| **App Android TV nativa, questo progetto** | Copre USB, D-pad, musica, giochi, cache privata e Cast Connect. Richiede configurazione OAuth e registrazione Cast per le rispettive funzioni. | Nessun costo operativo dell’app. Possono applicarsi i costi degli account sviluppatore Google Play e Cast. | Media |
| **Usare solo la Modalità Ambient di Google Foto** | È l’alternativa più leggera e non richiede sviluppo, ma non offre USB, musica personalizzata, transizioni complete, giochi o protocollo Cast dell’app. | Nessun costo dell’app personalizzata. | Bassa |
| **Sender web/mobile con ricevitore Web Cast** | Funziona anche su Chromecast senza app Android TV installata, ma l’accesso USB locale e l’esperienza TV nativa sono più limitati. | Hosting HTTPS e registrazione Cast. | Medio-alta |

Il progetto implementa il primo approccio perché è l’unico che soddisfa contemporaneamente tutti i requisiti. Le altre due opzioni restano alternative valide se vengono eliminate funzioni essenziali.

## Installazione rapida dell’APK di prova

1. Attivare **Opzioni sviluppatore** e **Debug USB/rete** sulla Google TV.
2. Collegare `adb` alla TV.
3. Installare il file:

```bash
adb install -r dist/FotoFesta-TV-debug.apk
```

L’APK debug è firmato con certificato di sviluppo. Per una distribuzione reale usare l’Android App Bundle e una chiave di release, preferibilmente tramite Play App Signing.

## Compilazione

```bash
./scripts/setup_android_sdk.sh
./scripts/build.sh
```

La guida [CONFIGURATION.md](docs/CONFIGURATION.md) descrive Google Foto, Cast Connect e firma release. Il protocollo è specificato in [CAST_PROTOCOL.md](docs/CAST_PROTOCOL.md).

## Architettura e flussi dati

La lettura USB usa `ACTION_OPEN_DOCUMENT_TREE`, che concede accesso solo alla cartella scelta e alle sue sottocartelle.[3] Google Foto usa il nuovo Picker, perché dal 31 marzo 2025 la Library API non consente più a un’app di elencare l’intera libreria dell’utente.[1] L’utente seleziona esplicitamente le foto dal telefono; l’app scarica in modo automatico una copia temporanea nella propria cache privata, necessaria perché le `baseUrl` della Picker API scadono dopo circa 60 minuti.[2]

Il flusso OAuth usa il meccanismo Google per TV e dispositivi a input limitato: la TV mostra URL e codice e l’utente autorizza da un telefono.[8] Il solo scope richiesto è `photospicker.mediaitems.readonly`; una pubblicazione pubblica richiede la verifica OAuth di Google.[9]

Cast Connect segue il modello Sender/Receiver di Google Cast. Il ricevitore Android TV accetta comandi nel namespace `urn:x-cast:it.fotofesta.tv` e aggiorna la `MediaSession` per play, pausa e skip.[4] La registrazione assegna l’Application ID che dovrà essere condiviso con gli eventuali sender Android, iOS o Web.[10]

## Privacy e sicurezza

L’app non integra pubblicità, analytics, crash reporting esterno o un backend proprietario. Gli URI USB rimangono sul televisore. I token Google sono cifrati con una chiave AES-GCM non esportabile conservata da Android Keystore. La cache Google Foto si trova nell’area privata dell’app e può essere cancellata dalla schermata impostazioni. Il traffico HTTP in chiaro è disabilitato e il protocollo Cast accetta soltanto URL HTTPS.

Queste scelte applicano minimizzazione e privacy by design. Prima di una pubblicazione pubblica devono comunque essere completate l’informativa definitiva, la scheda Data Safety e la verifica OAuth in base al titolare effettivo e ai paesi di distribuzione.[7] [9]

## Compatibilità e qualità

Il progetto usa `minSdk 26`, `targetSdk 35` e `compileSdk 35`. Il target supera il minimo richiesto per nuove app Android TV nel 2026.[6] Il manifest espone `LEANBACK_LAUNCHER`, dichiara il touchscreen non necessario e include un banner TV. I controlli principali sono utilizzabili con D-pad; nello slideshow i controlli si nascondono dopo 5 secondi, centro alterna play/pausa, sinistra/destra cambiano foto e su/giù mostrano la barra dei comandi, dove le frecce spostano il focus tra i pulsanti. La foto successiva viene precaricata per transizioni senza attese. Il pacchetto include ABI a 32 e 64 bit e ha superato il controllo `zipalign -P 16` per l’allineamento 16 KB.[5] [11]

L’architettura separa modello, repository e protocollo dall’interfaccia. Questo permette di riutilizzare il protocollo Cast e la logica di dominio per un futuro sender iOS senza compromettere il client Android TV.

## Limiti intenzionali della prima versione

Google Foto non espone più l’intera libreria a un’app di terze parti: l’utente deve selezionare i contenuti per ogni nuova raccolta. La cache viene sostituita alla selezione successiva. Il progetto mostra immagini, non video. Il sottofondo musicale è un file locale scelto sulla TV e non viene inviato dal sender Cast. La modalità screensaver di sistema non è inclusa in questa prima build, perché alcuni produttori limitano i salvaschermi di terze parti; lo slideshow può però restare acceso finché è in esecuzione.

## Verifica effettuata

Ogni push e ogni pull request verso `main` eseguono in GitHub Actions i 21 test unitari (protocollo Cast e validazione URL, ordine di riproduzione, ordinamento dei file, risposte HTTP Google), Android Lint e la build dell’APK debug (`.github/workflows/android.yml`); l’APK e i report sono scaricabili come artefatti. Il rapporto di audit e le correzioni applicate sono descritti in [AUDIT.md](docs/AUDIT.md).

Risultati della validazione della versione 1.0.0 (vedi [VALIDATION.md](docs/VALIDATION.md)):

| Controllo | Risultato |
| --- | --- |
| Compilazione APK debug | Superata |
| Compilazione AAB release con shrinking R8 | Superata |
| Test unitari protocollo Cast | 5/5 superati |
| Android Lint | 0 problemi |
| Firma APK debug | Verificata, schema v2 |
| ABI | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` |
| Allineamento 16 KB | Superato |

## Riferimenti

[1]: https://developers.google.com/photos/support/updates "Updates to the Google Photos APIs"
[2]: https://developers.google.com/photos/picker/guides/media-items "List and retrieve media items"
[3]: https://developer.android.com/training/data-storage/shared/documents-files "Access documents and other files from shared storage"
[4]: https://developers.google.com/cast/docs/android_tv_receiver/core_features "Add Core Features to Your Android TV Receiver"
[5]: https://developer.android.com/docs/quality-guidelines/tv-app-quality "Android TV app quality"
[6]: https://support.google.com/googleplay/android-developer/answer/11926878 "Target API level requirements for Google Play apps"
[7]: https://commission.europa.eu/law/law-topic/data-protection/data-protection-eu_en "Legal framework of EU data protection"
[8]: https://developers.google.com/identity/protocols/oauth2/limited-input-device "OAuth 2.0 for TV and Limited-Input Device Applications"
[9]: https://developers.google.com/photos/overview/authorization "Google Photos API authorization scopes"
[10]: https://developers.google.com/cast/docs/registration "Google Cast application registration"
[11]: https://developer.android.com/guide/practices/page-sizes "Support 16 KB page sizes"
