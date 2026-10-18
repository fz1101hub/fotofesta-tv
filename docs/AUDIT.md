# Audit del progetto

**Data:** 6 ottobre 2026
**Base:** versione 1.0.0 (`versionCode 1`), commit iniziale

L’audit ha riguardato codice Kotlin, manifest, build Gradle, script e documentazione. I problemi sono ordinati per gravità; tutti quelli segnati come **corretto** sono stati risolti in questa revisione.

## Bug funzionali

| # | Gravità | Area | Problema | Stato |
| --- | --- | --- | --- | --- |
| 1 | Alta | Google Foto | La scadenza della selezione nel Picker confrontava il tempo trascorso dall’inizio con il `timeoutIn` *residuo* dell’ultima risposta: la sessione veniva dichiarata scaduta circa a metà del tempo reale. | Corretto: la scadenza viene ricalcolata a ogni polling. |
| 2 | Alta | Google Foto | Con un refresh token revocato o scaduto (`invalid_grant`) ogni tentativo falliva sempre con lo stesso errore; l’unica via d’uscita era “Disconnetti e cancella”. Lo stesso per un access token revocato (HTTP 401). | Corretto: i token non validi vengono cancellati e il codice QR viene riproposto. |
| 3 | Alta | Google Foto | Il download cancellava subito la cache esistente: un errore o un annullamento lasciava lo slideshow puntato a file eliminati. Le foto con download fallito a metà restavano in cache come file troncati. | Corretto: download in cartella temporanea e sostituzione atomica; file parziali eliminati. |
| 4 | Alta | Musica | `setDataSource` su un brano rimosso o non più accessibile lanciava un’eccezione non gestita sul thread principale (crash). Una pausa durante la preparazione asincrona veniva ignorata e la musica partiva comunque. L’`OnErrorListener` chiamava il `release()` del `MediaPlayer` invece di quello del lettore, lasciando un riferimento a un player rilasciato. | Corretto, con messaggio all’utente e gestione del focus audio (pausa/abbassamento se un’altra app riproduce audio). |
| 5 | Alta | Giochi | `Modifier.blur` funziona solo da Android 12: su Android TV 8–11 (la maggioranza dei televisori) la “Foto misteriosa” era mostrata nitida. Inoltre TalkBack leggeva il titolo della foto nascosta. | Corretto: su Android < 12 si usa una versione a bassissima risoluzione; descrizione neutra finché la foto è nascosta. |
| 6 | Media | Slideshow | Le frecce sinistra/destra erano intercettate anche quando il focus era sui pulsanti: impossibile spostarsi tra i comandi con il D-pad. La barra copriva sempre la foto e l’orologio si aggiornava solo al cambio foto (fermo in pausa). | Corretto: barra a scomparsa, frecce sui pulsanti quando sono a fuoco, orologio aggiornato ogni minuto nel formato 12/24 h di sistema. |
| 7 | Media | Navigazione | Il tasto Indietro durante il collegamento Google tornava alla home lasciando attivo il polling in background, che poteva poi sostituire la raccolta senza preavviso. La sessione Picker non veniva eliminata in caso di errore o annullamento, e un errore nella sua eliminazione invalidava un download riuscito. | Corretto: Indietro annulla il flusso, la sessione viene sempre chiusa (best effort). |
| 8 | Media | Coroutine | `runCatching` intercettava anche `CancellationException`, e le chiamate OkHttp bloccanti non si interrompevano annullando la coroutine. | Corretto: chiamate HTTP annullabili e cancellazione propagata. |
| 9 | Media | Shuffle | L’ordine casuale estraeva una foto a caso a ogni passo: ripetizioni frequenti e “Precedente” non tornava alla foto vista prima. | Corretto: permutazione completa per giro, senza ripetere subito l’ultima foto. |
| 10 | Media | Cast | Il comando `SETTINGS` reimpostava ai valori predefiniti tutti i campi non inviati. I comandi arrivati prima che la UI fosse pronta (es. `LOAD` subito dopo l’avvio) andavano persi perché lo `SharedFlow` senza iscritti li scartava. Id foto duplicati dal sender rompevano il gioco delle coppie e le animazioni. | Corretto: aggiornamento parziale, coda dei comandi, id univoci. |
| 11 | Media | Cast | La `MediaSession` non era collegata a Cast Connect (`setSessionCompatToken`), quindi i comandi multimediali standard dei sender non raggiungevano l’app. | Corretto. |
| 12 | Bassa | Google Foto | `pageToken` e `sessionId` venivano concatenati negli URL senza codifica. Dopo un riavvio i titoli delle foto in cache diventavano nomi file tecnici e l’ordine di selezione andava perso. Il parametro `state` inviato all’endpoint device code non fa parte del flusso OAuth per TV. | Corretto: URL costruiti con `HttpUrl`, manifest con titoli e ordine. |
| 13 | Bassa | Giochi | Con una sola foto i giochi erano banali; la stessa foto poteva essere riproposta subito nella “Foto misteriosa”. | Corretto: minimo due foto, nessuna ripetizione immediata, contatore mosse in Trova la coppia. |
| 14 | Bassa | Script | `setup_android_sdk.sh` sovrascriveva `local.properties`, cancellando le credenziali OAuth già configurate. | Corretto: aggiorna solo `sdk.dir`. |

## Sicurezza e privacy

| # | Gravità | Problema | Stato |
| --- | --- | --- | --- |
| 15 | Media | Il filtro degli URL Cast confrontava l’host IPv6 senza togliere le parentesi (`[::1]` passava) e non riconosceva IPv4 mappati in IPv6, CGNAT, multicast e forme numeriche ambigue (`0177.0.0.1`, `2130706433`). Al contrario, rifiutava domini pubblici che iniziano per `fc`, `fd` o `fe8`–`feb` (es. `feature.example.com`). | Corretto e coperto da test (`RemoteUrlPolicy`). |
| 16 | Bassa | Il backup Android includeva i token Google cifrati (inutilizzabili su un altro dispositivo) e tutte le preferenze. | Corretto: regole di backup limitate alle impostazioni dello slideshow. |
| 17 | Bassa | Permesso `WAKE_LOCK` dichiarato ma non usato (lo schermo resta acceso con `FLAG_KEEP_SCREEN_ON`). | Rimosso. |
| 18 | Info | Il `gradle-wrapper.jar` corrisponde al checksum ufficiale di Gradle 8.10.2; nessun segreto presente nel repository. | Verificato; la CI valida il wrapper a ogni build. |

## Prestazioni

| # | Problema | Stato |
| --- | --- | --- |
| 19 | La scansione USB con `DocumentFile.listFiles()` esegue tre query per ogni file: lentissima con migliaia di foto. Venivano inclusi anche i file nascosti `._*.jpg` di macOS e formati non decodificabili (RAW, TIFF, PSD). | Corretto: una query per cartella, filtro dei file nascosti e dei formati non supportati, ordinamento naturale (`IMG_2` prima di `IMG_10`). |
| 20 | Download Google sequenziali e senza controllo dello spazio libero. | Corretto: quattro download in parallelo, arresto sotto 300 MB liberi. |
| 21 | Il QR veniva generato con 262.144 chiamate `setPixel` sul thread principale. | Corretto: un pixel per modulo, scrittura unica e ingrandimento senza interpolazione. |
| 22 | La foto successiva iniziava a caricarsi solo durante la transizione (rotella di caricamento a metà dissolvenza per USB e URL remoti). | Corretto: precaricamento alla risoluzione dello schermo. |

## Esperienza d’uso e accessibilità

- Le foto non leggibili (USB scollegato, file corrotto) mostrano un segnaposto “Foto non disponibile” invece di uno schermo nero.
- La home assegna subito il focus ad “Avvia slideshow”, così la prima pressione del telecomando non va persa; durante l’indicizzazione USB appare una barra di avanzamento.
- Le carte di Trova la coppia hanno descrizioni per TalkBack (“Carta coperta 3”, “Coppia trovata: …”).
- I titoli delle foto USB non mostrano più l’estensione del file.

## Strumenti e build

- Aggiunta la CI GitHub Actions (`.github/workflows/android.yml`): validazione del wrapper, test unitari, lint, APK debug come artefatto.
- Firma release opzionale tramite `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (vedi [CONFIGURATION.md](CONFIGURATION.md)).
- I test unitari sono passati da 5 a 21. La logica non Android (ordine di riproduzione, ordinamento naturale, validazione URL, errori HTTP Google, durate protobuf) è stata estratta in classi testabili.

### Verifica di questa revisione

I 21 test unitari sono stati compilati ed eseguiti con Kotlin 2.0.21 su JVM 17: tutti superati, senza avvisi del compilatore. L’ambiente in cui è stato svolto l’audit non poteva scaricare l’Android SDK (`dl.google.com` bloccato dalla policy di rete), quindi la build completa dell’APK e Android Lint **non** sono stati eseguiti localmente: la prima esecuzione della nuova CI li verifica. Restano necessari i test manuali su TV reale elencati in [VALIDATION.md](VALIDATION.md).

## Seconda revisione (versione 1.1.0)

- **Revisione indipendente** del codice Android non compilabile in questo ambiente: nessun errore di compilazione trovato. Le API Coil sono state verificate sul bytecode reale, i costrutti Kotlin a rischio con kotlinc 2.0.21. Sono stati corretti i cinque problemi minori segnalati:
  - `next()`/`previous()` modificavano l’ordine di riproduzione dentro `MutableStateFlow.update {}`, che può essere rieseguito;
  - il precaricamento poteva rimescolare l’ordine durante la composizione;
  - un `MediaPlayer` restava aperto se `setDataSource` falliva;
  - la “Foto misteriosa” poteva apparire già risolta dopo un cambio di raccolta;
  - la sostituzione della cache Google ora sposta da parte la cache precedente e la ripristina se lo scambio fallisce.
- **Localizzazione**: tutti i testi dell’interfaccia, i messaggi e gli errori sono in `strings.xml`, con plurali corretti. L’inglese è la lingua predefinita per i televisori in lingue non tradotte; l’italiano è in `values-it`. Gli errori mostrati all’utente non sono più messaggi tecnici delle eccezioni ma testi localizzati (rete assente, servizio Google non disponibile, codice scaduto…). Un test unitario verifica che le due lingue abbiano le stesse chiavi e gli stessi segnaposto.
- La cache Google viene letta all’avvio fuori dal thread principale.
- Versione portata a 1.1.0 (`versionCode 2`). Test unitari: 24, tutti superati con kotlinc 2.0.21.

## Terza revisione (versione 1.2.0)

- **Flusso Google Foto testabile**: i repository OAuth e Picker non dipendono più da Android (credenziali, archivio token, endpoint, cartella cache e orologio sono iniettati) e il flusso di importazione è uscito dal ViewModel in `GoogleImportFlow`. 17 test lo verificano contro un server Google finto (MockWebServer):
  - autorizzazione con `authorization_pending` e `slow_down`;
  - rinnovo e revoca dei token;
  - nuovo collegamento dopo un 401;
  - paginazione e codifica dei parametri;
  - download parziali e spazio insufficiente;
  - cache precedente preservata;
  - manifest con percorsi fuori dalla cache;
  - sessione Picker eliminata anche in caso di errore.
- **Movimento lento (Ken Burns)**: zoom e spostamento lenti durante la permanenza di ogni foto, fermi in pausa e disattivati da “Riduci movimento”. Lo spostamento è una quota del margine creato dallo zoom, quindi i bordi della foto non entrano mai nell’inquadratura (verificato su 500 foto).
- **Pausa automatica** dopo 30, 60 o 120 minuti, con l’orario previsto nella barra dei comandi. Lo schermo resta acceso solo mentre lo slideshow è in riproduzione: in pausa la TV può andare in standby.
- **Salvaschermo di sistema** (`PhotoDreamService`): mostra l’ultima raccolta con dissolvenza lenta, movimento lento e orologio che cambia angolo ogni minuto per non segnare gli schermi OLED. Dalle impostazioni un pulsante apre la scelta del salvaschermo; il README spiega come attivarlo via `adb` sulle Google TV che nascondono il menu.
- Protocollo Cast: nuovi campi `kenBurns` e `sleepTimerMinutes`.
- Test unitari: 46, tutti superati con kotlinc 2.0.21.

## Audit finale per la pubblicazione

Tre revisioni indipendenti: app Android, app Apple TV, pubblicazione (CI, fastlane, documenti, conformità alle regole degli store).

App Android:
- **Barra dei comandi dello slideshow**: in italiano i cinque pulsanti con etichetta lasciavano circa 13 dp al titolo, che andava a capo una lettera per riga e alzava la barra fino all'85% dello schermo. Ora titolo e dettagli stanno su una riga sopra i pulsanti, Precedente e Successiva sono solo icone (con descrizione per TalkBack) e i testi non vanno a capo.
- **Home a 960×540 dp**: il contenuto superava l'altezza dello schermo, e con il testo grande della TV l'ultima riga di pulsanti spariva. Spaziature ridotte, testi delle schede su due righe al massimo e scorrimento automatico quando serve.
- **“Disconnetti e cancella”**: la revoca presso Google poteva fallire se l'utente usciva dall'app durante la cancellazione dei file, perché il client HTTP veniva chiuso con il ViewModel. Ora il client è unico per il processo, i token locali si cancellano per primi e i file si eliminano in parallelo, anche se un'importazione annullata stava salvando proprio in quel momento. Un nuovo collegamento aspetta la fine della cancellazione, che altrimenti potrebbe eliminare i nuovi token o la cartella del download.
- **Pulizia dell'archivio all'avvio**: con l'ultima sorgente USB veniva annullata prima di partire, quindi le cartelle di download interrotti si accumulavano. Ora è un'operazione separata che importazione e cancellazione aspettano.
- **Indietro subito dopo il download**: scartava un'importazione già salvata su disco, lasciando l'elenco precedente con file ormai sostituiti. Ora le foto si mostrano appena salvate, senza aspettare la chiusura della sessione Picker, e un annullamento in quell'istante riallinea l'elenco ai file presenti.
- **Salvaschermo dopo un aggiornamento**: leggeva solo il nuovo archivio, quindi mostrava le foto demo finché non si apriva l'app. Ora legge anche la vecchia cache.
- **Foto demo**: decodificate a piena risoluzione (2560×1440) sul thread principale. Ora passano da Coil, ridotte alla dimensione mostrata e precaricate come le altre.
- **Schermo acceso durante il collegamento a Google**, così QR e codice non spariscono mentre si usa il telefono.
- **HEIC/HEIF e AVIF** sono accettati da USB solo sulle versioni di Android che li sanno aprire (9 e 12).
- **Area sicura delle TV**: margini laterali di 48 dp in tutte le schermate e fuoco visibile sul testo della privacy.
- **Backup**: cartella USB, brano e ultima sorgente spostati in un file escluso dal backup, perché i permessi sugli URI non passano a un altro dispositivo.
- **ID pubblicità**: il manifest rimuove esplicitamente il permesso `AD_ID`.

App Apple TV (nessun errore di compilazione trovato leggendo il codice riga per riga):
- **Menu durante il collegamento a Google**: nelle schermate di attesa non c'era nulla a fuoco, quindi Menu chiudeva la schermata senza annullare il collegamento, che continuava in background e poteva sostituire le foto più tardi. Ora c'è un pulsante Annulla e l'uscita dalla schermata annulla comunque il collegamento.
- **Build senza client Google**: il file di esempio conteneva valori finti, quindi l'app mostrava Google Foto e falliva con un errore di Google. Ora i valori di esempio sono vuoti, senza client la scheda Google non compare, il messaggio è comprensibile per l'utente e fastlane rifiuta un client di esempio.
- **Simbolo iCloud** usato per Google Foto: Apple riserva quei simboli a iCloud. Sostituito.
- **Note per i revisori**: citavano un account di test anche quando non veniva inviato. Ora la frase si aggiunge solo se l'account è fornito.
- **Trova la coppia**: una nuova partita durante il controllo di una coppia rovinava il nuovo tavolo. Ora il controllo è legato alla partita.
- **Informativa nell'app**: tvOS non apre link, quindi le impostazioni mostrano l'informativa come QR. Aggiunto anche il testo breve dell'informativa per Apple TV nei metadati.
- **Cache svuotata da tvOS mentre l'app è sospesa**: problema superato dallo streaming (vedi sotto), perché le foto non si salvano più in Caches.
- **Movimento lento dopo una pausa**: alla ripresa il cambio foto aspettava di nuovo l'intervallo intero, con l'immagine ferma. Ora aspetta solo il tempo restante.
- **Avanzamento del download** che poteva tornare indietro, **disconnessione** che poteva cancellare un'importazione appena avviata, **Indietro durante la chiusura della sessione** con l'elenco non allineato ai file: corretti come su Android.
- **Passi di un collegamento annullato** ignorati da quello nuovo (ogni collegamento ha un identificativo); la disconnessione aspetta anche un'importazione già annullata con Annulla o Menu.
- **fastlane** legge correttamente un client Google vuoto e, per l'App Store, si ferma se la build non avrebbe Google Foto mentre la scheda lo descrive.
- **Numero di build** assegnato da fastlane a ogni caricamento su TestFlight; simbolo di foto non disponibile compatibile con tvOS 17; “Riduci movimento” di sistema rispettato.
- Al primo avvio dopo una nuova installazione si cancellano i token rimasti nel Portachiavi, che sopravvive alla disinstallazione.

Pubblicazione:
- **Pagina di supporto e informativa** spostate in `site/`, con un workflow che pubblica solo quella cartella e si ferma se restano campi da completare. La guida spiega le alternative a GitHub Pages per un repository privato, la home page e la verifica del dominio per Google OAuth.
- **Sicurezza dei dati**: il Cast SDK di Google invia dati di utilizzo e diagnostica, quindi la risposta corretta non è “nessun dato”. Aggiornati `DATA_SAFETY.md`, informativa, scheda Play e testo nell'app.
- **fastlane**: il file `mapping.txt` non veniva mai caricato su Play (percorso relativo sbagliato). Il Fastfile Apple si ferma se trova segnaposto e legge l'account di test da variabili d'ambiente invece che da file nel repository. Versioni di fastlane fissate in `Gemfile.lock`.
- **CI**: la verifica della firma dell'AAB non falliva mai (`jarsigner -verify` termina con 0 anche su file non firmati). Trigger limitati a `main` e alle pull request. Nuovo workflow macOS per compilare l'app Apple TV.
- **Requisiti Apple**: Xcode 26 obbligatorio da aprile 2026; parole chiave senza marchi di terzi e senza funzioni assenti su tvOS.
- **Icona Play** quadrata e opaca (PNG a 32 bit), perché Play applica da sé gli angoli arrotondati.
- **Informativa**: aggiunte le foto ricevute con Google Cast, che restano temporaneamente nella cache dell'app, e precisato che i dati del Cast SDK comprendono identificatori del dispositivo.
- Documenti allineati: dimensioni del banner, test, stato di verifica reale. `PRIVACY_POLICY_TEMPLATE.md` eliminato, sostituito da `site/privacy/index.html`.

Test unitari: 54, tutti superati con kotlinc 2.0.21. La build Gradle e Lint della 1.2.0 restano da eseguire con l'Android SDK, e l'app Apple TV da compilare con Xcode.

## Google Foto in streaming

Su richiesta, le foto di Google Foto non vengono più scaricate: le smart TV hanno poca memoria.

- Sul dispositivo resta solo un piccolo elenco: sessione Picker, sua scadenza, identificativi e nomi delle foto. Su Android è un file JSON in `no_backup`, su Apple TV sta in UserDefaults.
- Ogni foto si carica da Google già ridotta alla dimensione mostrata e resta solo nella cache in RAM. Su Android passa da un Fetcher Coil dedicato, su Apple TV da una sessione di rete effimera, senza cache su disco.
- Gli indirizzi della Picker API valgono circa 60 minuti. L'app li rinnova richiedendo di nuovo l'elenco della sessione ogni 50 minuti, o subito dopo un 401/403; le foto che ricevono lo stesso errore in parallelo condividono un solo rinnovo di token ed elenco. La cache in RAM distingue le dimensioni, così una miniatura di Trova la coppia non viene riusata a tutto schermo.
- Dopo un elenco rifiutato per altri motivi l'app aspetta un minuto prima di richiederlo, invece di ripetere la richiesta a ogni foto. Un token rifiutato durante un nuovo collegamento si prova prima a rinnovare, così le foto mostrate restano raggiungibili.
- Su Apple TV una selezione è limitata a 1000 foto, perché l'elenco sta nelle preferenze, che su tvOS hanno spazio ridotto.
- La sessione resta aperta finché Google la tiene valida (`expireTime`, la data è mostrata a fine collegamento). Quando Google conferma che non esiste più (404, oppure 400/403 confermati da `sessions.get` o dopo la scadenza indicata da Google), l'app torna alle foto demo e chiede di scegliere di nuovo le foto; se l'accesso è stato revocato chiede un nuovo collegamento. La scadenza della sessione appena sostituita non tocca la selezione nuova, e l'orologio della TV da solo non cancella nulla.
- Passando a un'altra sorgente (demo, USB, Cast) la selezione si cancella e la sessione si chiude presso Google.
- “Disconnetti e cancella” cancella l'elenco, svuota la cache in RAM, chiude la sessione presso Google e revoca l'accesso.
- All'avvio si eliminano le foto scaricate dalle versioni precedenti.
- Il salvaschermo usa la stessa selezione, quindi richiede la connessione.
- Su Android la cache su disco di Coil è limitata a 32 MB e contiene solo le foto inviate con Google Cast.

Test JVM: 67, tutti superati, e la CI Android (build, test, Lint, APK) è verde. I test XCTest equivalenti sono stati aggiornati, ma vanno eseguiti su un Mac.

## Foto degli invitati e rimozione di Google Cast (Android 1.3.0, Apple TV 1.1.0)

Su richiesta, le app dei telefoni non possono trasmettere foto dentro FotoFesta con Google Cast o AirPlay: entrambi mostrano le foto con il ricevitore di sistema, fuori dall'app. Al loro posto gli invitati inquadrano un QR e mandano le foto da una pagina servita dalla TV, sulla rete di casa. Google Cast è stato rimosso (SDK, ricevitore, protocollo, cache su disco di Coil), quindi l'app Android non raccoglie più dati. Il funzionamento è descritto in [CONFIGURATION.md](CONFIGURATION.md#3-foto-degli-invitati).

Due revisioni indipendenti (Android e Swift) hanno trovato questi problemi, tutti corretti:

| Gravità | Problema | Correzione |
| --- | --- | --- |
| Alta | Android: lo spazio libero si misurava su una cartella non ancora creata (0 byte), quindi le foto non passavano mai su disco e venivano cancellate appena finita la RAM | Si misura la prima cartella esistente del percorso; test con lo spazio reale |
| Alta | Apple TV: due errori di compilazione (capacità del volume non disponibile su tvOS; variabile modificata in una closure `@Sendable`) | API disponibile su tvOS; indicatore senza variabile catturata |
| Media | Lo slideshow si fermava sulla stessa foto mentre arrivavano foto nuove (il conteggio ripartiva a ogni spostamento dell'indice) | Il cambio automatico segue `slideTick`, che cresce solo a un cambio voluto |
| Media | Dopo una foto nuova in fondo all'elenco la stessa foto compariva due volte di seguito | Dopo le foto nuove si riprende saltando quelle appena viste |
| Media | In ordine casuale ogni foto nuova rimescolava tutto il giro | `PlaybackOrder.remap`: le foto nuove si inseriscono tra quelle non ancora viste |
| Media | Server fermato dal sistema (rete tolta) con il QR ancora sullo schermo | Avviso al coordinatore e riavvio automatico; avvio non riuscito ritentato |
| Media | Una connessione lentissima poteva occupare il server; con molti invii insieme le connessioni venivano chiuse senza risposta | Tempi totali per intestazioni (15 s) e corpo (120 s), 3 connessioni e 60 invii al minuto per telefono, 24 MB in arrivo, risposta 503/429 con `Retry-After` |
| Media | La pagina sul telefono si bloccava per sempre dopo un errore temporaneo | Nuovi tentativi per circa un minuto, poi "Riprova"; una nuova scelta di foto riprende la coda |
| Media | Un invitato poteva far cancellare le foto approvate mandando molte foto | Prima si sacrificano le foto in attesa, una foto in attesa non fa mai cancellare foto approvate, massimo 100 foto in attesa |
| Media | Apple TV: lo slideshow avviato dalla festa poteva lasciar partire il salvaschermo | Schermo acceso deciso in un solo punto in base alla schermata |
| Bassa | Foto aggiunta dopo la fine della festa; errori inattesi nel server che chiudevano l'app; foto ripetute dopo un nuovo tentativo; foto lette mentre venivano approvate; QR di rete aperta se la password non era leggibile; password da riscrivere modificando la rete; salvaschermo che partiva prima della ripresa della festa; anteprime a piena risoluzione nella pagina; foto HEIC senza spiegazione | `stop()` che aspetta le richieste; errori intercettati; identificativo per invio; lettura con il lock; controllo della password; campo già compilato; attesa della ripresa; miniature; messaggio dedicato |

Verifiche: 113 test JVM superati e CI Android verde (build, test, Lint, APK); dopo il browser delle cartelle i test sono 122. La pagina per i telefoni è stata provata in Chromium (profili Pixel 7 e iPhone 13) contro il server della TV: riduzione e rotazione corrette, metadati rimossi, file non immagine rifiutati, TV spenta e riaccesa con "Riprova" che completa gli invii. Il codice Swift ha avuto due ulteriori revisioni dopo le correzioni; la CI Apple TV su macOS 15 lo compila per tvOS senza errori e i 65 test XCTest del nucleo passano.

## Nuove funzioni (Android 1.4.0, Apple TV 1.2.0)

Aggiunti su entrambe le piattaforme: Puzzle con tre livelli (6, 12 e 24 pezzi), “Chi l’ha scattata?”, premi di fine festa, risposte dai telefoni degli invitati in “Foto misteriosa” e “Chi l’ha scattata?”, mosaico, conto alla rovescia (mezzanotte, torta, brindisi, sorpresa), video nello slideshow (escludibili, con audio facoltativo) e un nuovo stile per Apple TV con i colori di tvOS. Solo su Android TV: cartelle dei server multimediali della rete di casa (DLNA), video da USB e rete, musica da una cartella intera. “Memory” si chiama ora “Trova la coppia”.

Problemi trovati e corretti durante lo sviluppo:

| Gravità | Problema | Correzione |
| --- | --- | --- |
| Alta | Da Android 13 una cartella USB letta dal percorso mostra solo i tipi di file permessi: i video sarebbero rimasti invisibili | Chiesto anche `READ_MEDIA_VIDEO`; per caricare la cartella basta il permesso delle foto, così chi l’aveva concesso solo per le foto non resta bloccato |
| Alta | Cartella principale di un NAS con molta musica: la ricerca delle foto entrava negli alberi di album, artisti e generi ed esauriva il limite di cartelle prima delle foto | Le cartelle che per classe UPnP sono solo musica o playlist si saltano (provato anche contro MiniDLNA) |
| Media | DLNA: la copertina JPEG di un brano o di un video poteva diventare una “foto” | Il tipo si decide dalla classe UPnP dell’elemento (`imageItem`, `videoItem`, `audioItem`) |
| Media | I server DLNA parlano solo HTTP in chiaro, vietato dalla configurazione di rete | Client dedicato (`LanHttp`) limitato agli indirizzi privati, anche dopo un reindirizzamento; XML con DOCTYPE rifiutato; la configurazione resta invariata |
| Media | AVPlayer non permette di aggiungere il token Google all’indirizzo di un video | `AVAssetResourceLoaderDelegate` con richieste Range e rinnovo del token se scade durante il video |
| Media | Apple TV: “Chi l’ha scattata?” restava una schermata vuota (una vista vuota non riceve `onAppear`) | Sfondo sempre presente che avvia il gioco |
| Media | tvOS 26: la tinta globale nascondeva le etichette dei pulsanti senza fuoco; un’immagine ritagliata usciva dal suo riquadro e spingeva fuori schermo “Foto misteriosa” | Tinta globale e ruoli distruttivi tolti; immagine in un `overlay` che non cambia le dimensioni |
| Bassa | Android: schermata del Puzzle non compilata (import mancante); `weight` importato come funzione | Import corretti, verificati dalla CI |
| Bassa | Pannello del gioco con i telefoni troppo alto con quattro nomi e la classifica, su Apple TV | Pannello orizzontale sotto la foto, classifica dei primi tre |

Verifiche: 184 test JVM (Android) e 104 test XCTest (Apple TV) superati; CI Android (build, test, Lint, APK) e CI Apple TV verdi. Il client delle cartelle di rete è stato provato contro un vero server MiniDLNA; la pagina per i telefoni con il gioco in Chromium contro una TV simulata. Le schermate dal simulatore tvOS (`[screenshots]`) mostrano puzzle, giochi, festa, premi, mosaico, conto alla rovescia e il gioco con due telefoni simulati che rispondono.

## Nuove sorgenti: cartelle condivise, iCloud, cartelle di rete su Apple TV

Aggiunti su Android TV: cartelle condivise SMB 2/3 di PC, Mac e NAS (ricerca Bonjour, indirizzo scritto, accesso con password o come ospite, password cifrata nel Keystore), album condivisi di iCloud con il link pubblico (link incollato dal telefono con un QR servito dalla TV, aggiornamento ogni 5 minuti) e foto dei server DLNA nella versione più leggera che copre lo schermo. Su Apple TV: Foto di iCloud dalla libreria Foto con PhotoKit e cartelle di rete DLNA (nucleo portato da Android, con test).

Problemi trovati e corretti durante lo sviluppo:

| Gravità | Problema | Correzione |
| --- | --- | --- |
| Alta | SMB: se il NAS chiude la connessione (standby, riavvio), smbj continua a considerarla aperta e ogni lettura successiva falliva con “DiskShare has already been closed” | Una condivisione chiusa dal server fa ripartire anche sessione e connessione; gli errori di smbj senza stato SMB contano come connessione caduta e si riprova una volta. Provato uccidendo il processo di Samba durante l'uso |
| Alta | Release Android: R8 si fermava sulle classi `java.rmi` (dcerpc) e `javax.el` (MBassador), assenti su Android | Regole `-dontwarn` mirate e regole `-keep` per smbj, MBassador e dcerpc; l'errore di collegamento possibile su una risposta DCE/RPC malformata è gestito. La CI ora compila anche la release con R8 |
| Media | Pacchetto Android: file doppi (`META-INF/versions/9/OSGI-INF/MANIFEST.MF` di Bouncy Castle e jspecify, licenze) | Esclusi nel `packaging`; le licenze delle librerie sono in `assets/licenses/THIRD_PARTY_NOTICES.txt` |
| Media | smbj apre le connessioni senza limite di tempo: verso un indirizzo spento si aspettavano minuti | `SocketFactory` con 8 s per collegarsi |
| Media | NAS Synology: le miniature in `@eaDir` sarebbero diventate foto doppie | Saltati file nascosti e di sistema, `@…`, `#…`, `$…`, `~…`, “System Volume Information” |
| Media | Apple TV: con PhotoKit l'orientamento delle foto si perdeva convertendo in `CGImage` | La `UIImage` di PhotoKit si tiene così com'è |
| Media | Apple TV: la ricerca SSDP in multicast richiede un entitlement concesso da Apple | Domanda SSDP diretta e porte dei server più diffusi per l'indirizzo scritto; entitlement attivabile da `App.xcconfig` dopo l'approvazione |
| Bassa | La password delle cartelle condivise poteva finire nei log tramite `toString()` | `SmbLogin.toString()` la omette |

Verifiche: 215 test JVM (Android; 8 contro un vero server Samba 4 con `FOTOFESTA_SMB_TEST`) e 114 test XCTest (Apple TV); CI Android (test, Lint, APK debug, release con R8) e CI Apple TV. Il servizio dell'album iCloud non era raggiungibile dall'ambiente di sviluppo (rete bloccata): il client è scritto sul comportamento documentato da progetti open source e provato contro un server finto, e va provato con un album reale.

## Emulatore Android TV (1.4.0)

La CI apre l'app nell'emulatore Android TV 14 a 1080p in 32 schermate, in cinque lingue, con un Samba e un server DLNA di prova sul runner (vedi [VALIDATION.md](VALIDATION.md#emulatore-android-tv-140)). I test JVM non vedono l'interfaccia: le schermate hanno mostrato questi problemi, corretti e verificati nel giro successivo.

| Gravità | Problema | Correzione |
| --- | --- | --- |
| Media | Impostazioni: tornando in Musica dopo aver scelto un brano, o in Festa dalla Wi‑Fi, il fuoco cadeva sulla prima sezione, che con il fuoco si apre: le impostazioni tornavano a Slideshow | All'apertura il fuoco va sulla sezione in uso |
| Media | Campi di testo (cartelle condivise, link iCloud, Wi‑Fi della festa): la tastiera si apriva da sola con il fuoco e copriva metà schermata, compresi il messaggio d'errore e la didascalia del QR. `showKeyboardOnFocus = false` e `stateHidden` non bastavano: Compose la apre per ogni campo che riceve il fuoco | `TvTextField`: le frecce si fermano su una cornice e il campo vero riceve il fuoco, con la tastiera, solo dopo OK. Verificato nell'emulatore premendo OK |
| Media | Gioco con i telefoni: in spagnolo la classifica finiva tagliata in fondo al pannello (e con più giocatori sarebbe successo in ogni lingua) | Con la classifica il QR lascia il posto ai primi tre nomi; spiegazione al massimo su quattro righe |
| Media | Pulsanti tornati attivi ("Connetti" dopo un errore, "Mostra la foto" a foto caricata) restavano senza sfondo | Ombra e trasparenza in livelli sempre presenti, invece di `shadow()` e `alpha()` che aggiungono e tolgono un livello |
| Bassa | Home: "Impostazioni" ed "Einstellungen" tagliati nella scheda stretta; descrizioni delle sorgenti tagliate a una riga; "Photos de démo" e "Fotos de demostración" tagliati | Scheda più larga, descrizioni su due righe e più corte dove non stavano; "Photos démo" e "Fotos demo" |
| Bassa | Giochi: descrizioni di tre righe tagliate senza puntini; la fila finiva tagliata di netto sul margine | Schede più alte con i puntini; la fila arriva al bordo dello schermo |

## Audit indipendente su `main` (18 ottobre 2026)

Un audit esterno ha analizzato la revisione `e32ec4c`, cioè `main` prima della pull request: l'import iniziale con 24 file Kotlin, 5 test, Google Cast e senza CI. Dei 19 rilievi, 17 riguardano codice che la PR ha riscritto o rimosso (Cast, cache delle foto Google, thread e annullamento del collegamento Google, MediaPlayer, limite USB, selettore di documenti, sfocatura su Android 8–11, contrasto, backup, script SDK, orologio, errori delle immagini). Restano:

| Rilievo | Stato sul branch |
| --- | --- |
| F01 Device flow OAuth con lo scope Photos Picker | **Corretto** con il consenso dal telefono (vedi "Nuovo flusso Google" sotto): Google documenta per il flusso "TV e dispositivi con input limitato" un elenco chiuso di scope (Sign-In, Drive, YouTube) che non comprende `photospicker.mediaitems.readonly` |
| F19 "Riduci movimento" non toccava le animazioni del fuoco | Corretto: spegne anche l'ingrandimento del fuoco e la dissolvenza delle foto nella home |
| Licenza del codice e provenienza degli asset | Nessun file `LICENSE`; le foto demo sono immagini generate (`design/`), il carattere Figtree ha la OFL in `assets/licenses` |
| `distributionSha256Sum` nel wrapper Gradle e verifica delle dipendenze | Non presenti |

## Secondo audit indipendente sul branch (18 ottobre 2026, revisione `a777a1d`)

Lo stesso auditor ha rianalizzato il branch della pull request: confermato aperto F01, chiusi gli altri rilievi del primo audit e segnalati nove punti nuovi (N01–N09) più alcune note. Tutti verificati sul codice e corretti, tranne F01 che richiede il nuovo flusso di autorizzazione Google.

| Rilievo | Correzione |
| --- | --- |
| N01 La pagina per il link iCloud ripeteva il modulo, con la chiave del QR, anche nelle risposte 411 e 413, cioè a richieste che la chiave non avevano provato | Le pagine di errore non contengono più il modulo: la chiave compare solo nella pagina aperta con il QR |
| N02 Dopo `close()` una richiesta già accettata poteva ancora consegnare un link al ViewModel | Il server chiude anche le connessioni aperte e, dopo `stop()`, risponde "scaduto" senza chiamare il callback; il ViewModel accetta il link solo se la pagina è ancora quella in uso e la schermata del link è aperta |
| N03 Limiti insufficienti nel server del link: due thread senza coda limitata, nessun tetto per telefono, corpo letto senza scadenza complessiva | Esecutore con coda limitata (503 oltre), al massimo tre connessioni per telefono (429), scadenza complessiva anche sulla lettura del modulo, scarto del resto della richiesta prima di chiudere |
| N04 Apple TV: i reindirizzamenti di URLSession e di AVPlayer potevano portare fuori dalla rete di casa | Client HTTP con un delegato che segue un reindirizzamento solo verso indirizzi ammessi (https per Google, rete di casa per i server DLNA); i video dei server di casa passano dallo stesso caricatore dei video di Google (`StreamingVideoLoader`), che applica la stessa regola |
| N05 Apple TV: risposte della rete di casa lette senza tetto | Tetto in byte sul client HTTP (8 MB per le risposte DLNA, 16 MB di serie) e sulle foto dei server di casa (64 MB) |
| N06 Android: il client OkHttp dei video aveva `callTimeout` di 90 secondi, che spezzava un video più lungo | Client dedicato ai video senza limite complessivo per chiamata; restano i limiti di connessione e di lettura |
| N07 Android: un token Google o un indirizzo iCloud rifiutati a metà video non si rinnovavano | `RenewingDataSource`: dopo 401/403 (Google) o 403/404/410 (iCloud) chiede a `GooglePhotosStream`/`ICloudAlbumStream` un rinnovo, indicando cosa non ha funzionato, e riprova una volta |
| N08 `LanHttp`: per un nome che risolve in IPv6 l'intestazione Host portava il nome fra parentesi quadre | Host com'è scritto nell'indirizzo: un nome resta un nome, un indirizzo IPv6 numerico tiene le sue parentesi |
| N09 Pagina degli invitati: una risposta al gioco rifiutata dalla TV (409, 429, 503) era mostrata come data | La pagina controlla la risposta: se rifiutata, il pulsante torna attivo e, se la domanda è chiusa, lo dice |
| Nota: nessuna regola di rete per SMB | La TV parla SMB solo con indirizzi della rete di casa (come per DLNA): il controllo precede la connessione, un nome si risolve una volta sola e la ricerca Bonjour scarta gli indirizzi esterni; messaggio dedicato in cinque lingue |
| Nota: Apple TV, `GoogleVideoLoader` con la configurazione `.default` | Sessione effimera, senza cache su disco |
| Nota: i server degli invitati accettavano richieste senza intestazione Host | Host obbligatorio su Android e Apple TV (421 senza) |
| Nota: `LICENSE`, `distributionSha256Sum`, verifica delle dipendenze | Invariati: la licenza del progetto la sceglie il proprietario; la somma del wrapper e la verifica delle dipendenze sono in `docs/PUBLISHING.md` fra le cose da fare |
| F01 Device flow OAuth con lo scope Photos Picker | **Corretto** con il nuovo flusso (sotto) |

Verifiche: test JVM nuovi per la pagina del link (chiave assente dalle pagine di errore, connessioni chiuse da `stop()` senza link consegnati, limite per telefono), per Host obbligatorio, per l'intestazione Host di `LanHttp`, per la regola di rete SMB e per la ricerca Bonjour; su Apple TV un test del client HTTP (tetto in byte) e del server degli invitati senza Host. `RenewingDataSource` non ha un test JVM: `DataSpec` richiede un `Uri` di Android, assente nei test senza Robolectric.

## Nuovo flusso Google: consenso dal telefono (F01)

Il flusso per TV di Google (device flow) non ammette lo scope del Photos Picker, quindi il collegamento non avrebbe mai funzionato con credenziali vere. Al suo posto, su entrambe le piattaforme:

1. la TV serve sulla rete di casa una pagina (`shared/assets/guest/google.html`, con le stesse regole della pagina degli invitati: chiave nel QR, `Host`, connessioni e tempi limitati, dieci minuti al massimo) e ne mostra il QR;
2. il telefono apre la pagina, tocca «Continua con Google» e va alla schermata di consenso di Google con il flusso "authorization code" e PKCE (client di tipo "Applicazione web"; il `code_verifier` resta sulla TV); lo `state` porta in base64url l'indirizzo di ritorno sulla TV;
3. Google rimanda il telefono a `site/google/`, una pagina statica del sito che legge lo `state`, accetta solo indirizzi privati e riporta il telefono alla TV con il codice (una navigazione, ammessa da https a http);
4. la TV consegna il codice una volta sola, lo toglie dall'indirizzo del browser con un 303, lo scambia con i token e offre al telefono anche il link del Picker, così le foto si scelgono senza un secondo QR.

Scelte: il client secret dell'applicazione web resta nell'app (il progetto non ha un server), come il secret del vecchio client per TV; serve solo insieme al codice protetto da PKCE. Il consenso tramite Google Play Services sulla TV è stato scartato perché userebbe l'account della TV, mentre le foto stanno di solito sul telefono dell'organizzatore. Verifiche: test JVM e XCTest dell'indirizzo di autorizzazione (PKCE, scope, `state`), dello scambio del codice, del flusso completo con un telefono finto e della pagina della TV su loopback (chiave, `Host`, ritorno dal sito, codice consegnato una volta sola, link del Picker); in Chromium (profilo telefono, italiano e tedesco) la catena intera contro il server JVM vero e la pagina del sito servita in locale: pulsante verso Google con lo `state` giusto, ritorno con il codice fino a `#done` e al pulsante del Picker, annullamento, `state` verso un indirizzo pubblico rifiutato dal sito, chiave sbagliata rifiutata dalla TV. Non provato con credenziali vere: resta fra le prove su TV reale, con il sito pubblicato.

## Raccomandazioni aperte

| Priorità | Raccomandazione |
| --- | --- |
| Alta | Eseguire sulla TV i flussi modificati: collegamento Google (incluso annullamento e token revocato), USB con migliaia di foto, Indietro e barra dei comandi nello slideshow, Foto misteriosa su Android 9–11, salvaschermo, movimento lento e pausa automatica. |
| Bassa | I titoli predefiniti generati per le foto senza nome (“Foto N”) e i messaggi interni di log restano in italiano. |
| Bassa | Adottare `androidx.tv:tv-material` per componenti con focus e indicazioni TV native. |
| Media | Provare le foto degli invitati su TV reali con telefoni Android e iPhone: rete Wi‑Fi e via cavo, rete ospiti del router, uscita e ritorno nell'app, festa lunga con centinaia di foto. |
| Bassa | Prima di portare `targetSdk` oltre 35, verificare se Android richiede il nuovo permesso per la rete locale anche per un server in ascolto. |
| Bassa | Valutare l'approvazione delle foto attiva per impostazione predefinita, se le prove mostrano invii indesiderati. |
| Alta | Provare le cartelle di rete con server reali (NAS, Plex o Jellyfin, router) e i video su TV diverse: formati, codec non supportati, video lunghi, audio insieme alla musica. Su Apple TV, i video di Google Foto. |
