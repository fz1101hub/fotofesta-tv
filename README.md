# FotoFesta TV

**FotoFesta TV** è un’app Android nativa per Android TV e Google TV che mostra fotografie in slideshow da una cartella USB, da una selezione sicura in Google Foto oppure dai telefoni degli invitati. Gli ospiti inquadrano un QR sulla TV e mandano le foto attraverso la rete di casa, senza installare nulla. Include durata regolabile, tre transizioni, adattamento immagine, ordine casuale, musica locale, controlli D-pad e due minigiochi basati sulle fotografie attive.

Il repository contiene anche la versione per **Apple TV** (cartella [`apple/`](apple/README.md)), scritta in Swift e SwiftUI, con le stesse funzioni tranne USB, musica e salvaschermo, che tvOS non consente.

> **Stato del progetto:** versione 1.3.0 per Android TV e 1.1.0 per Apple TV, pronte per la prima pubblicazione dopo le verifiche indicate in [Verifica effettuata](#verifica-effettuata). L’app funziona subito con foto demo, foto degli invitati, USB, musica e giochi. Per Google Foto occorre inserire un proprio client OAuth. La procedura completa per Google Play e App Store è in [PUBLISHING.md](docs/PUBLISHING.md).

![Architettura di FotoFesta TV](docs/architecture.png)

## Funzionalità implementate

| Area | Funzione | Stato |
| --- | --- | --- |
| Sorgenti | Foto demo incluse | Pronta |
| Sorgenti | Foto degli invitati: QR sulla TV, pagina web servita dalla TV, foto ridotte sul telefono e inviate sulla rete di casa | Pronta; nessun account, nessun server esterno |
| Sorgenti | Cartella su hard disk USB scelta con il browser interno (o con il selettore di Android, se la TV lo ha) | Pronta; nessuna copia delle foto |
| Sorgenti | Google Photos Picker API con autorizzazione TV e QR | Pronta nel codice; richiede credenziali OAuth del proprietario |
| Festa | Foto nuove mostrate subito, nome del mittente, approvazione facoltativa, QR nell’angolo dello slideshow, QR della Wi‑Fi | Pronta |
| Slideshow | Durata 3, 5, 8, 12 o 20 secondi | Pronta |
| Slideshow | Dissolvenza, scorrimento e zoom dolce | Pronta |
| Slideshow | Foto intera o riempimento schermo, shuffle, orologio | Pronta |
| Slideshow | Movimento lento (effetto Ken Burns) e pausa automatica dopo 30, 60 o 120 minuti | Pronta |
| Accessibilità | Focus visibile, D-pad, tasti media, riduzione movimento, contrasto elevato | Pronta |
| Audio | Brano locale selezionato dall’utente, loop e volume | Pronta |
| Salvaschermo | Foto dell’ultima raccolta quando la TV è inattiva, con dissolvenza, movimento lento e orologio che cambia angolo | Pronta |
| Giochi | Memory dei ricordi | Pronta |
| Giochi | Foto misteriosa | Pronta |
| Privacy | Nessuna pubblicità, analisi o SDK che inviano dati; token AES-GCM in Android Keystore ([DATA_SAFETY.md](docs/DATA_SAFETY.md)) | Pronta |
| Lingue | Italiano e inglese (predefinito per le altre lingue del televisore) | Pronta |
| Google Play | Banner Leanback 320×180 nell’app; per la scheda icona 512×512, grafica 1024×500 e banner TV 1280×720; AAB firmato | Pronta |
| Apple TV | App tvOS 17+ con icona a livelli, Top Shelf, italiano e inglese | Da compilare e provare su un Mac ([apple/README.md](apple/README.md)) |

## Scelta dell’approccio

| Approach | Tradeoffs | Cost | Setup Complexity |
| --- | --- | --- | --- |
| **App TV nativa con server locale, questo progetto** | Copre USB, D-pad, musica, giochi, Google Foto senza copie sulla TV e foto degli invitati senza account né server. Gli invitati devono essere sulla stessa rete della TV. | Nessun costo operativo dell’app. Possono applicarsi i costi degli account sviluppatore Google Play e Apple. | Media |
| **Usare solo la Modalità Ambient di Google Foto** | È l’alternativa più leggera e non richiede sviluppo, ma non offre USB, musica personalizzata, transizioni complete, giochi o foto degli invitati. | Nessun costo dell’app personalizzata. | Bassa |
| **Foto degli invitati attraverso un servizio cloud** | Funzionerebbe anche con i telefoni in rete mobile, ma le foto passerebbero da un server dello sviluppatore, con costi, manutenzione e obblighi privacy. | Hosting, archiviazione e traffico. | Alta |

Il progetto implementa il primo approccio perché è l’unico che soddisfa contemporaneamente tutti i requisiti senza far uscire le foto di casa. Google Cast non viene usato: le app dei telefoni che “trasmettono” lo fanno solo verso i propri ricevitori, quindi non potrebbero mostrare le foto dentro FotoFesta.

## Installazione rapida dell’APK di prova

1. Attivare **Opzioni sviluppatore** e **Debug USB/rete** sulla Google TV.
2. Collegare `adb` alla TV.
3. Installare il file:

```bash
adb install -r dist/FotoFesta-TV-debug.apk
```

In alternativa l’APK di prova si scarica dalla pagina dell’esecuzione di **Android CI** in GitHub Actions (artefatto `FotoFesta-TV-debug`).

L’APK debug è firmato con una chiave di sviluppo fissa e pubblica (`app/debug.keystore`, password `android`), così ogni nuova build di prova si installa sopra la precedente e conserva le impostazioni. Le build di prova scaricate prima della 1.3.0 avevano una chiave diversa a ogni esecuzione: la prima volta va disinstallata la vecchia. Per una distribuzione reale usare l’Android App Bundle e una chiave di release, preferibilmente tramite Play App Signing.

## Compilazione

```bash
./scripts/setup_android_sdk.sh
./scripts/build.sh
```

La guida [CONFIGURATION.md](docs/CONFIGURATION.md) descrive Google Foto, le foto degli invitati e la firma release. Per pubblicare su Google Play e App Store segui [PUBLISHING.md](docs/PUBLISHING.md). L’app Apple TV si compila con Xcode come spiegato in [apple/README.md](apple/README.md).

## Architettura e flussi dati

Cartella USB e brano si scelgono con un browser di cartelle interno, comandato dal telecomando, perché molte TV non hanno il selettore di documenti di Android (`ACTION_OPEN_DOCUMENT_TREE`) e mostrano “nessuna applicazione può gestire questa azione”. Il browser elenca i dischi montati e legge i file dal loro percorso con il permesso di lettura delle foto o della musica (`READ_MEDIA_IMAGES` e `READ_MEDIA_AUDIO` da Android 13, `READ_EXTERNAL_STORAGE` prima), chiesto solo quando si apre. Se la TV ha il selettore di sistema, resta disponibile come alternativa e concede accesso solo alla cartella scelta.[3] Google Foto usa il nuovo Picker, perché dal 31 marzo 2025 la Library API non consente più a un’app di elencare l’intera libreria dell’utente.[1] L’utente seleziona esplicitamente le foto dal telefono. L’app non le scarica: conserva solo identificativi e nomi e carica ogni foto da Google alla dimensione dello schermo, tenendola soltanto in memoria. Le `baseUrl` della Picker API scadono dopo circa 60 minuti, quindi l’app le rinnova richiedendo di nuovo l’elenco della sessione, finché Google la tiene aperta.[2]

Il flusso OAuth usa il meccanismo Google per TV e dispositivi a input limitato: la TV mostra URL e codice e l’utente autorizza da un telefono.[7] Il solo scope richiesto è `photospicker.mediaitems.readonly`; una pubblicazione pubblica richiede la verifica OAuth di Google.[8]

Le **foto degli invitati** non passano da internet. Quando l’organizzatore avvia la festa, la TV apre un piccolo server HTTP sulla rete di casa (porta 8765, oppure un’altra libera) e mostra un QR con il suo indirizzo e una chiave casuale. Il telefono dell’invitato apre la pagina servita dalla TV ([`shared/assets/guest/index.html`](shared/assets/guest/index.html), la stessa per Android TV e Apple TV), che riduce ogni foto alla risoluzione dello schermo, la ricodifica in JPEG senza metadati (posizione, modello del telefono) e la invia. La TV tiene le foto in RAM; quando la memoria assegnata non basta, quando il sistema lo chiede o quando l’app va in background, le più vecchie passano in una cartella temporanea con limite (100 MB, 300 MB sulle TV che disegnano in 4K, mai più del 10% dello spazio libero). Se anche quella è piena, le più vecchie fanno posto alle nuove. “Termina la festa” cancella tutto; dopo 12 ore senza foto la festa si chiude da sola al successivo avvio. Il server resta acceso solo con l’app in primo piano. I dettagli sono in [CONFIGURATION.md](docs/CONFIGURATION.md#3-foto-degli-invitati).

## Privacy e sicurezza

L’app non integra pubblicità, analytics, crash reporting esterno, SDK di terze parti che inviano dati o un backend proprietario: le risposte per la scheda Sicurezza dei dati sono in [DATA_SAFETY.md](docs/DATA_SAFETY.md). Il percorso della cartella USB e del brano musicale rimangono sul televisore e sono esclusi dal backup. I token Google e la password della Wi‑Fi per il QR degli invitati sono cifrati con una chiave AES-GCM non esportabile conservata da Android Keystore. Le foto Google non vengono copiate sulla TV; “Disconnetti e cancella” rimuove l’elenco delle foto scelte, chiude la sessione Picker e revoca l’accesso. Il traffico HTTP in uscita in chiaro è disabilitato. Il server per gli invitati accetta solo chi conosce la chiave del QR, controlla l’intestazione `Host`, il formato e le dimensioni delle immagini, limita intestazioni, tempi e connessioni e serve la pagina con una Content Security Policy restrittiva. L’informativa privacy e la pagina di supporto da pubblicare sono in [`site/`](site/).

Queste scelte applicano minimizzazione e privacy by design. Prima di una pubblicazione pubblica devono comunque essere completate l’informativa definitiva, la scheda Data Safety e la verifica OAuth in base al titolare effettivo e ai paesi di distribuzione.[6] [8]

## Compatibilità e qualità

Il progetto usa `minSdk 26`, `targetSdk 35` e `compileSdk 35`. Il target supera il minimo richiesto per nuove app Android TV nel 2026.[5] Il manifest espone `LEANBACK_LAUNCHER`, dichiara il touchscreen non necessario e include un banner TV. I controlli principali sono utilizzabili con D-pad; nello slideshow i controlli si nascondono dopo 5 secondi, centro alterna play/pausa, sinistra/destra cambiano foto e su/giù mostrano la barra dei comandi, dove le frecce spostano il focus tra i pulsanti. La foto successiva viene precaricata per transizioni senza attese. Il pacchetto include ABI a 32 e 64 bit; la versione 1.0.0 ha superato il controllo `zipalign -P 16` per l’allineamento 16 KB, da ripetere sull’AAB di ogni release.[4] [9]

L’architettura separa modello, archivi e server dall’interfaccia, così la logica si verifica con test JVM e XCTest senza un televisore.

## Limiti intenzionali

Google Foto non espone più l’intera libreria a un’app di terze parti: l’utente deve selezionare i contenuti per ogni nuova raccolta, e la selezione vale finché Google tiene aperta la sessione Picker (la data è mostrata a fine collegamento); poi le foto vanno scelte di nuovo. Per vederle serve la connessione a internet. Il progetto mostra immagini, non video.

Per le foto degli invitati i telefoni devono essere collegati alla stessa rete della TV: con i dati mobili la pagina non si apre. Le reti “ospiti” di alcuni router isolano i dispositivi tra loro e bloccano l’invio; in quel caso telefoni e TV vanno collegati alla rete principale. Sui telefoni che non sanno aprire le foto HEIC nel browser (alcuni Android) quelle foto vengono segnalate come non supportate.

Il salvaschermo di sistema mostra l’ultima raccolta scelta nell’app (Google Foto, cartella USB o foto demo, con le foto approvate degli invitati se la festa è in corso) usando durata, adattamento, movimento lento e orologio delle impostazioni. Alcuni produttori, tra cui le Google TV recenti, nascondono la scelta dei salvaschermi di terze parti; in quel caso si può attivare via `adb`:

```bash
adb shell settings put secure screensaver_components it.fotofesta.tv/.dream.PhotoDreamService
adb shell settings put secure screensaver_enabled 1
```

Per tornare al salvaschermo predefinito basta scegliere un altro salvaschermo dalle impostazioni oppure ripetere il primo comando con il componente originale, letto prima con `adb shell settings get secure screensaver_components`.

## Verifica effettuata

**Android TV.** I 122 test unitari (ordine di riproduzione e foto nuove degli invitati, movimento lento, ordinamento dei file, browser delle cartelle USB, coerenza delle traduzioni, flusso Google Foto e streaming contro un server finto, archivio delle foto degli invitati con RAM e cartella di riserva, server HTTP con richieste vere su loopback, limiti contro gli abusi, QR della Wi‑Fi) passano con Kotlin 2.0.21 su JVM 17. La pagina per i telefoni è stata provata in Chromium (profili Pixel 7 e iPhone 13) contro il server della TV: riduzione e rotazione corrette, metadati rimossi, file non immagine rifiutati, TV spenta e riaccesa con “Riprova” che completa gli invii. In GitHub Actions ogni push su `main` e ogni pull request eseguono test, Android Lint e build dell’APK debug (`.github/workflows/android.yml`). Gli audit e le correzioni sono descritti in [AUDIT.md](docs/AUDIT.md).

La versione 1.3.0 compila e supera test e Android Lint in GitHub Actions, che produce anche l’APK debug scaricabile dagli artefatti. Prima del primo caricamento servono la build di release firmata (`release.yml` o `./scripts/build.sh`) e la prova manuale su una TV reale descritta in [VALIDATION.md](docs/VALIDATION.md) e in [PUBLISHING.md](docs/PUBLISHING.md#3-verifiche-finali-prima-di-inviare).

Risultati della validazione locale della versione 1.0.0:

| Controllo | Risultato 1.0.0 |
| --- | --- |
| Compilazione APK debug | Superata |
| Compilazione AAB release con shrinking R8 | Superata |
| Test unitari | 5/5 superati |
| Android Lint | 0 problemi |
| Firma APK debug | Verificata, schema v2 |
| ABI | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` |
| Allineamento 16 KB | Superato |

**Apple TV.** Il workflow `.github/workflows/apple.yml` su un runner macOS 15 con Xcode 26.3 (SDK tvOS 26, quello richiesto da App Store Connect) esegue i 65 test XCTest del nucleo, tutti superati, e compila l’app per tvOS senza firma, senza errori né avvisi Swift. Restano da fare la prova nel simulatore e su una Apple TV reale e l’archivio firmato descritto in [apple/README.md](apple/README.md).

## Riferimenti

[1]: https://developers.google.com/photos/support/updates "Updates to the Google Photos APIs"
[2]: https://developers.google.com/photos/picker/guides/media-items "List and retrieve media items"
[3]: https://developer.android.com/training/data-storage/shared/documents-files "Access documents and other files from shared storage"
[4]: https://developer.android.com/docs/quality-guidelines/tv-app-quality "Android TV app quality"
[5]: https://support.google.com/googleplay/android-developer/answer/11926878 "Target API level requirements for Google Play apps"
[6]: https://commission.europa.eu/law/law-topic/data-protection/data-protection-eu_en "Legal framework of EU data protection"
[7]: https://developers.google.com/identity/protocols/oauth2/limited-input-device "OAuth 2.0 for TV and Limited-Input Device Applications"
[8]: https://developers.google.com/photos/overview/authorization "Google Photos API authorization scopes"
[9]: https://developer.android.com/guide/practices/page-sizes "Support 16 KB page sizes"
