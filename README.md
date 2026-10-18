# FotoFesta TV

**FotoFesta TV** è un’app Android nativa per Android TV e Google TV che mostra foto e video in slideshow da una cartella USB, da un server multimediale della rete di casa (NAS, router, PC con DLNA), dalle cartelle condivise di PC, Mac e NAS (SMB), da un album condiviso di iCloud con il link pubblico, da una selezione sicura in Google Foto oppure dai telefoni degli invitati. Gli ospiti inquadrano un QR sulla TV e mandano le foto attraverso la rete di casa, senza installare nulla. Include durata regolabile, tre transizioni, mosaico, conto alla rovescia, adattamento immagine, ordine casuale, musica locale (un brano o una cartella), controlli D-pad, premi di fine festa e quattro giochi con le fotografie attive, a cui gli invitati possono rispondere dal telefono.

Il repository contiene anche la versione per **Apple TV** (cartella [`apple/`](apple/README.md)), scritta in Swift e SwiftUI, con le stesse funzioni tranne USB, musica e salvaschermo, che tvOS non consente, e le cartelle condivise SMB; in più legge gli album della libreria Foto della Apple TV (Foto di iCloud e album condivisi).

> **Stato del progetto:** versione 1.4.0 per Android TV e 1.2.0 per Apple TV, pronte per la prima pubblicazione dopo le verifiche indicate in [Verifica effettuata](#verifica-effettuata). L’app funziona subito con foto demo, foto degli invitati, USB, cartelle di rete e condivise, album iCloud, musica e giochi. Per Google Foto occorre inserire un proprio client OAuth. La procedura completa per Google Play e App Store è in [PUBLISHING.md](docs/PUBLISHING.md).

![Architettura di FotoFesta TV](docs/architecture.png)

## Funzionalità implementate

| Area | Funzione | Stato |
| --- | --- | --- |
| Sorgenti | Foto demo incluse | Pronta |
| Sorgenti | Foto degli invitati: QR sulla TV, pagina web servita dalla TV, foto ridotte sul telefono e inviate sulla rete di casa | Pronta; nessun account, nessun server esterno |
| Sorgenti | Cartella su hard disk USB scelta con il browser interno (o con il selettore di Android, se la TV lo ha) | Pronta; nessuna copia delle foto |
| Sorgenti | Cartelle dei server multimediali della rete di casa (DLNA/UPnP: NAS, router con disco, PC con condivisione multimediale, Plex, Jellyfin), senza password, con la versione delle foto che basta allo schermo | Pronta su Android TV (provata contro MiniDLNA) e Apple TV (server trovati da soli con l’entitlement multicast di Apple, altrimenti con l’indirizzo) |
| Sorgenti | Cartelle condivise (SMB 2/3) di PC Windows, Mac e NAS: computer trovati con Bonjour o aggiunti con l’indirizzo, accesso con utente e password o come ospite | Pronta su Android TV; provata contro Samba |
| Sorgenti | Album condiviso di iCloud con “Sito web pubblico”: link incollato dal telefono con un QR o codice scritto con il telecomando; foto nuove dell’album aggiunte da sole | Pronta su Android TV (servizio Apple non documentato) |
| Sorgenti | Libreria Foto della Apple TV: Foto di iCloud, preferiti, album e album condivisi, con le foto nuove aggiunte da sole | Pronta su Apple TV |
| Sorgenti | Video da USB, rete di casa, cartelle condivise, iCloud e Google Foto, guardati fino alla fine; escludibili, con audio facoltativo | Pronta (Android: Media3; Apple TV: AVPlayer, da Google Foto, libreria Foto e cartelle di rete) |
| Sorgenti | Google Photos Picker API con autorizzazione TV e QR | Pronta nel codice; richiede credenziali OAuth del proprietario |
| Festa | Foto nuove mostrate subito, nome del mittente, approvazione facoltativa, QR nell’angolo dello slideshow, QR della Wi‑Fi | Pronta |
| Festa | Premi di fine festa: obiettivo d’oro a chi ha mandato più foto, prima foto, gran finale | Pronta |
| Slideshow | Durata 3, 5, 8, 12 o 20 secondi | Pronta |
| Slideshow | Dissolvenza, scorrimento e zoom dolce | Pronta |
| Slideshow | Foto verticali con sfondo sfocato della stessa foto, bande nere o riempimento schermo; shuffle, orologio | Pronta |
| Slideshow | Didascalia con nome significativo o cartella (non “IMG_2024…”), data di scatto o mittente, barra di avanzamento | Pronta |
| Slideshow | Movimento lento (effetto Ken Burns) e pausa automatica dopo 30, 60 o 120 minuti | Pronta |
| Slideshow | Mosaico: la foto nuova in grande, accanto le ultime mostrate | Pronta |
| Slideshow | Conto alla rovescia verso un’ora scelta (mezzanotte, torta, brindisi, sorpresa), con gli ultimi secondi a tutto schermo | Pronta |
| Interfaccia | Android TV: stile ad alto contrasto (testi almeno 6:1), fuoco del telecomando in bianco pieno, carattere Figtree, impostazioni a sezioni. Apple TV: componenti nativi di tvOS (schede che si sollevano, pulsanti e impostazioni di sistema). Su entrambe la home mostra le foto della raccolta in uso | Pronta |
| Accessibilità | Focus visibile, D-pad, tasti media, riduzione movimento, contrasto elevato | Pronta |
| Audio | Brano locale o cartella intera come playlist, volume; si ferma mentre un video suona | Pronta su Android TV |
| Salvaschermo | Foto dell’ultima raccolta quando la TV è inattiva, con dissolvenza, movimento lento e orologio che cambia angolo | Pronta |
| Giochi | Trova la coppia | Pronta |
| Giochi | Foto misteriosa | Pronta |
| Giochi | Puzzle con tre livelli (6, 12 o 24 pezzi) | Pronta |
| Giochi | Chi l’ha scattata?: indovina quale invitato ha mandato la foto | Pronta |
| Giochi | Telefoni come pulsanti: in “Foto misteriosa” e “Chi l’ha scattata?” gli invitati rispondono dalla pagina della festa, con classifica | Pronta |
| Privacy | Nessuna pubblicità, analisi o SDK che inviano dati; token AES-GCM in Android Keystore ([DATA_SAFETY.md](docs/DATA_SAFETY.md)) | Pronta |
| Lingue | Italiano, inglese, francese, spagnolo e tedesco, anche nella pagina per i telefoni degli invitati, che segue la lingua del telefono; inglese per le altre lingue | Pronta |
| Google Play | Banner Leanback 320×180 nell’app; per la scheda icona 512×512, grafica 1024×500 e banner TV 1280×720; AAB firmato | Pronta |
| Apple TV | App tvOS 17+ con icona a livelli, Top Shelf, nelle stesse cinque lingue | Compilata e testata nella CI; da provare su una Apple TV ([apple/README.md](apple/README.md)) |

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

Le **cartelle di rete** usano DLNA/UPnP, il protocollo dei server multimediali di NAS, router, PC, Plex e Jellyfin: la TV li cerca con SSDP (una domanda in multicast sulla rete di casa), legge la descrizione del server e sfoglia le cartelle con l’azione `Browse` del servizio ContentDirectory. Non servono password. Foto e video si caricano dal server quando servono, senza copie sulla TV. I server DLNA parlano solo HTTP in chiaro, che l’app vieta nella configurazione di rete: lo usa soltanto un piccolo client dedicato (`LanHttp`) che si collega esclusivamente a indirizzi privati (192.168.x.x, 10.x.x.x, fd00::…), mai a internet, e rifiuta l’XML con DOCTYPE. Se il server cambia indirizzo IP la cartella si ritrova per identificativo (UDN). Album, artisti e generi musicali non vengono attraversati quando si cercano le foto.

Le **cartelle condivise** (solo Android TV) usano SMB 2 e 3 con la libreria [smbj](https://github.com/hierynomus/smbj): la TV trova i Mac e i NAS che si annunciano con Bonjour (`_smb._tcp`, con una domanda mDNS a cui rispondono direttamente) e accetta l’indirizzo scritto con il telecomando (`192.168.1.20`, `nas.local`, `\\NAS\Foto`), che serve per i PC Windows. L’accesso è con utente e password o come ospite; l’elenco delle condivisioni arriva con DCE/RPC (`srvsvc`) e, se il NAS non lo offre, se ne scrive il nome. La password è cifrata con una chiave del Keystore e resta sulla TV. Foto e video si leggono al momento, i video a blocchi da 256 KB per potersi spostare nel file; se il computer chiude la connessione (standby, riavvio) la TV la riapre da sola. Cestini e miniature dei NAS (`@eaDir`, `#recycle`, file nascosti) non diventano foto.

L’**album condiviso di iCloud** (solo Android TV) si apre con il link pubblico dell’album (“Sito web pubblico” attivo sull’iPhone). Il link non va scritto con il telecomando: la TV mostra un QR che apre sul telefono una pagina con un solo campo, servita dalla TV sulla rete di casa con una chiave casuale; in alternativa si scrive il codice che nel link segue “#”. La TV legge l’elenco dell’album dal servizio web che Apple usa per la pagina pubblica (non documentato: se Apple lo cambia, l’app lo segnala), chiede la versione delle foto adatta allo schermo e rilegge l’album ogni cinque minuti, così le foto aggiunte dagli iPhone compaiono da sole. Gli indirizzi dei file scadono e vengono rinnovati; nulla viene copiato sulla TV.

Su **Apple TV** la libreria Foto si legge con PhotoKit, dopo il permesso di tvOS: le foto arrivano già nella dimensione dello schermo, scaricate da iCloud se serve, e i video passano ad AVPlayer. Le cartelle di rete DLNA usano lo stesso protocollo di Android: la ricerca automatica in multicast richiede l’entitlement `com.apple.developer.networking.multicast`, che Apple concede su richiesta; finché non c’è, il server si aggiunge con l’indirizzo IP (domanda SSDP diretta e porte dei server più diffusi). L’http in chiaro è ammesso solo verso la rete locale (`NSAllowsLocalNetworking`) e l’app lo usa solo per indirizzi privati.

I **video** si guardano nello slideshow dall’inizio alla fine, poi si passa all’elemento successivo; la barra del tempo non compare. Su Android li riproduce Media3 (ExoPlayer) con i contenitori MP4, MOV, MKV, WebM, AVI, TS, MPEG, FLV e altri; i codec dipendono dalla TV e un video che la TV non sa decodificare mostra un avviso e viene saltato. Quelli della rete di casa passano da `LanHttp` con richieste `Range`, quelli delle cartelle condivise da smbj, quelli di iCloud dagli indirizzi che dà Apple e quelli di Google Foto vengono letti da Google con il token dell’utente (`=dv`), senza copie. Su Apple TV AVPlayer riproduce i video di Google Foto, della libreria Foto e delle cartelle di rete nei formati di tvOS (MP4 e MOV con H.264 o HEVC); per Google Foto il token viene aggiunto da un `AVAssetResourceLoaderDelegate`, perché AVPlayer non permette intestazioni personalizzate. Giochi, sfondo della home, caselle del mosaico e salvaschermo usano solo le foto.

Il **gioco con i telefoni** riusa la pagina della festa: la TV pubblica la domanda (`GET /game`) e i telefoni rispondono (`POST /game/answer`) con la stessa chiave del QR. Vale la prima risposta di ogni telefono; nomi e punti restano in memoria sulla TV e spariscono con la festa.

Il flusso OAuth usa il meccanismo Google per TV e dispositivi a input limitato: la TV mostra URL e codice e l’utente autorizza da un telefono.[7] Il solo scope richiesto è `photospicker.mediaitems.readonly`; una pubblicazione pubblica richiede la verifica OAuth di Google.[8]

Le **foto degli invitati** non passano da internet. Quando l’organizzatore avvia la festa, la TV apre un piccolo server HTTP sulla rete di casa (porta 8765, oppure un’altra libera) e mostra un QR con il suo indirizzo e una chiave casuale. Il telefono dell’invitato apre la pagina servita dalla TV ([`shared/assets/guest/index.html`](shared/assets/guest/index.html), la stessa per Android TV e Apple TV), che riduce ogni foto alla risoluzione dello schermo, la ricodifica in JPEG senza metadati (posizione, modello del telefono) e la invia. La TV tiene le foto in RAM; quando la memoria assegnata non basta, quando il sistema lo chiede o quando l’app va in background, le più vecchie passano in una cartella temporanea con limite (100 MB, 300 MB sulle TV che disegnano in 4K, mai più del 10% dello spazio libero). Se anche quella è piena, le più vecchie fanno posto alle nuove. “Termina la festa” cancella tutto; dopo 12 ore senza foto la festa si chiude da sola al successivo avvio. Il server resta acceso solo con l’app in primo piano. I dettagli sono in [CONFIGURATION.md](docs/CONFIGURATION.md#3-foto-degli-invitati).

## Privacy e sicurezza

L’app non integra pubblicità, analytics, crash reporting esterno, SDK di terze parti che inviano dati o un backend proprietario: le risposte per la scheda Sicurezza dei dati sono in [DATA_SAFETY.md](docs/DATA_SAFETY.md). Il percorso della cartella USB e del brano musicale rimangono sul televisore e sono esclusi dal backup. I token Google, la password della Wi‑Fi per il QR degli invitati e quella delle cartelle condivise sono cifrati con una chiave AES-GCM non esportabile conservata da Android Keystore. Le foto Google non vengono copiate sulla TV; “Disconnetti e cancella” rimuove l’elenco delle foto scelte, chiude la sessione Picker e revoca l’accesso. Il traffico HTTP in uscita in chiaro è disabilitato, tranne verso i server multimediali della rete di casa, raggiunti solo su indirizzi privati da un client dedicato. La pagina per incollare il link di iCloud funziona come quella degli invitati: chiave casuale nel QR, controllo di `Host`, moduli piccoli, accesa solo mentre la schermata è aperta. Il server per gli invitati accetta solo chi conosce la chiave del QR, controlla l’intestazione `Host`, il formato e le dimensioni delle immagini, limita intestazioni, tempi e connessioni e serve la pagina con una Content Security Policy restrittiva. L’informativa privacy e la pagina di supporto da pubblicare sono in [`site/`](site/).

Queste scelte applicano minimizzazione e privacy by design. Prima di una pubblicazione pubblica devono comunque essere completate l’informativa definitiva, la scheda Data Safety e la verifica OAuth in base al titolare effettivo e ai paesi di distribuzione.[6] [8]

## Compatibilità e qualità

Il progetto usa `minSdk 26`, `targetSdk 35` e `compileSdk 35`. Il target supera il minimo richiesto per nuove app Android TV nel 2026.[5] Il manifest espone `LEANBACK_LAUNCHER`, dichiara il touchscreen non necessario e include un banner TV. I controlli principali sono utilizzabili con D-pad; nello slideshow i controlli si nascondono dopo 5 secondi, centro alterna play/pausa, sinistra/destra cambiano foto e su/giù mostrano la barra dei comandi, dove le frecce spostano il focus tra i pulsanti. La foto successiva viene precaricata per transizioni senza attese. Il pacchetto include ABI a 32 e 64 bit; la versione 1.0.0 ha superato il controllo `zipalign -P 16` per l’allineamento 16 KB, da ripetere sull’AAB di ogni release.[4] [9]

L’architettura separa modello, archivi e server dall’interfaccia, così la logica si verifica con test JVM e XCTest senza un televisore.

## Limiti intenzionali

Google Foto non espone più l’intera libreria a un’app di terze parti: l’utente deve selezionare i contenuti per ogni nuova raccolta, e la selezione vale finché Google tiene aperta la sessione Picker (la data è mostrata a fine collegamento); poi le foto vanno scelte di nuovo. Per vederle serve la connessione a internet. Su Apple TV i video di Google Foto in formati che tvOS non riproduce (ad esempio MKV o AVI) vengono saltati.

Le cartelle di rete richiedono un server multimediale DLNA/UPnP acceso sulla stessa rete della TV. Sulla Apple TV i server si trovano da soli solo dopo che Apple ha concesso l’entitlement multicast; prima si aggiungono con l’indirizzo IP. Le cartelle condivise SMB sono solo su Android TV, con SMB 2 o 3 (il vecchio SMB 1 non è supportato); i PC Windows non si annunciano con Bonjour e si aggiungono con l’indirizzo. Come per gli invitati, le reti “ospiti” che isolano i dispositivi impediscono di trovare server e computer.

L’album condiviso di iCloud su Android TV usa il servizio web della pagina pubblica dell’album, che Apple non documenta e potrebbe cambiare: in quel caso l’app mostra un avviso e l’album va riaperto dopo un aggiornamento. Serve “Sito web pubblico” attivo; gli album privati e la libreria Foto di iCloud non sono raggiungibili da Android.

Per le foto degli invitati i telefoni devono essere collegati alla stessa rete della TV: con i dati mobili la pagina non si apre. Le reti “ospiti” di alcuni router isolano i dispositivi tra loro e bloccano l’invio; in quel caso telefoni e TV vanno collegati alla rete principale. Sui telefoni che non sanno aprire le foto HEIC nel browser (alcuni Android) quelle foto vengono segnalate come non supportate.

Il salvaschermo di sistema mostra le foto dell’ultima raccolta scelta nell’app (Google Foto, cartella USB, di rete o condivisa, album iCloud, foto demo, con le foto approvate degli invitati se la festa è in corso; i video no) usando durata, adattamento, movimento lento e orologio delle impostazioni. Alcuni produttori, tra cui le Google TV recenti, nascondono la scelta dei salvaschermi di terze parti; in quel caso si può attivare via `adb`:

```bash
adb shell settings put secure screensaver_components it.fotofesta.tv/.dream.PhotoDreamService
adb shell settings put secure screensaver_enabled 1
```

Per tornare al salvaschermo predefinito basta scegliere un altro salvaschermo dalle impostazioni oppure ripetere il primo comando con il componente originale, letto prima con `adb shell settings get secure screensaver_components`.

## Verifica effettuata

**Android TV.** I 215 test unitari (ordine di riproduzione e foto nuove degli invitati, movimento lento, ordinamento dei file, browser delle cartelle USB con i video, coerenza delle traduzioni, flusso Google Foto e streaming contro un server finto, archivio delle foto degli invitati con RAM e cartella di riserva, server HTTP con richieste vere su loopback, limiti contro gli abusi, QR della Wi‑Fi, puzzle, “Chi l’ha scattata?”, premi, mosaico, conto alla rovescia, gioco con i telefoni, client HTTP della rete di casa e cartelle DLNA contro un server finto con paginazione, sottocartelle, errori SOAP e reindirizzamenti, album iCloud contro un server finto, pagina per il link dal telefono, indirizzi e ricerca Bonjour delle cartelle condivise) passano con Kotlin 2.0.21 su JVM 17; 8 test delle cartelle condivise girano contro un vero server Samba indicato in `FOTOFESTA_SMB_TEST` e in CI si saltano. Il client delle cartelle di rete è stato provato anche contro un vero server MiniDLNA: cartelle, sottocartelle, viste virtuali senza doppioni e scaricamento delle foto. Quello delle cartelle condivise contro Samba 4: accesso con password e come ospite, elenco delle condivisioni, cartelle, lettura a pezzi, permessi negati, connessione chiusa dal server e riaperta. Il servizio dell’album iCloud non è stato raggiungibile dall’ambiente di sviluppo: va provato con un album reale (vedi [VALIDATION.md](docs/VALIDATION.md)). La pagina per i telefoni è stata provata in Chromium (profili Pixel 7 e iPhone 13) contro il server della TV: riduzione e rotazione corrette, metadati rimossi, file non immagine rifiutati, TV spenta e riaccesa con “Riprova” che completa gli invii. In GitHub Actions ogni push su `main` e ogni pull request eseguono test, Android Lint, build dell’APK debug e build della release con R8 (`.github/workflows/android.yml`). Con `[screenshots]` nel messaggio del commit la CI accende anche l’emulatore Android TV a 1080p: apre l’app in 32 schermate (festa con foto e risposte mandate come dai telefoni, cartelle condivise e di rete con video da un Samba e un server DLNA di prova) e cattura le schermate per Google Play in cinque lingue, che stanno in `fastlane/metadata/android/<lingua>/images/tvScreenshots/` (vedi [VALIDATION.md](docs/VALIDATION.md#emulatore-android-tv-140)). Gli audit e le correzioni sono descritti in [AUDIT.md](docs/AUDIT.md).

La versione 1.4.0 compila e supera test e Android Lint in GitHub Actions, che produce anche l’APK debug scaricabile dagli artefatti. Prima del primo caricamento servono la build di release firmata (`release.yml` o `./scripts/build.sh`) e la prova manuale su una TV reale descritta in [VALIDATION.md](docs/VALIDATION.md) e in [PUBLISHING.md](docs/PUBLISHING.md#3-verifiche-finali-prima-di-inviare).

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

**Apple TV.** Il workflow `.github/workflows/apple.yml` su un runner macOS 15 con Xcode 26.3 (SDK tvOS 26, quello richiesto da App Store Connect) esegue i 114 test XCTest del nucleo, tutti superati, e compila l’app per tvOS senza firma. Con `[screenshots]` nel messaggio del commit (o avviandolo a mano) fa anche le schermate dal simulatore, festa e gioco con i telefoni compresi, mandando foto e risposte come farebbero i telefoni. Restano da fare la prova nel simulatore e su una Apple TV reale e l’archivio firmato descritto in [apple/README.md](apple/README.md).

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

## Licenze di terze parti

Il carattere [Figtree](https://github.com/erikdkennedy/figtree), usato dall'app Android, è distribuito con la SIL Open Font License 1.1; il testo della licenza è incluso nell'app (`app/src/main/assets/licenses/Figtree-OFL.txt`). L'app Apple TV usa il carattere di sistema.

Le librerie dell'app Android (AndroidX, Media3, Coil, OkHttp, ZXing e, per le cartelle condivise, smbj, dcerpc, Bouncy Castle, MBassador, SLF4J, Guava e Apache Commons) hanno licenze Apache 2.0, BSD 3-Clause o MIT; l'elenco con i testi completi e gli avvisi richiesti è incluso nell'app (`app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt`).
