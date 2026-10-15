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
| 10 | Media | Cast | Il comando `SETTINGS` reimpostava ai valori predefiniti tutti i campi non inviati. I comandi arrivati prima che la UI fosse pronta (es. `LOAD` subito dopo l’avvio) andavano persi perché lo `SharedFlow` senza iscritti li scartava. Id foto duplicati dal sender rompevano Memory e animazioni. | Corretto: aggiornamento parziale, coda dei comandi, id univoci. |
| 11 | Media | Cast | La `MediaSession` non era collegata a Cast Connect (`setSessionCompatToken`), quindi i comandi multimediali standard dei sender non raggiungevano l’app. | Corretto. |
| 12 | Bassa | Google Foto | `pageToken` e `sessionId` venivano concatenati negli URL senza codifica. Dopo un riavvio i titoli delle foto in cache diventavano nomi file tecnici e l’ordine di selezione andava perso. Il parametro `state` inviato all’endpoint device code non fa parte del flusso OAuth per TV. | Corretto: URL costruiti con `HttpUrl`, manifest con titoli e ordine. |
| 13 | Bassa | Giochi | Con una sola foto i giochi erano banali; la stessa foto poteva essere riproposta subito nella “Foto misteriosa”. | Corretto: minimo due foto, nessuna ripetizione immediata, contatore mosse nel Memory. |
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
- Le carte del Memory hanno descrizioni per TalkBack (“Carta coperta 3”, “Coppia trovata: …”).
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
- **Memory**: una nuova partita durante il controllo di una coppia rovinava il nuovo tavolo. Ora il controllo è legato alla partita.
- **Informativa nell'app**: tvOS non apre link, quindi le impostazioni mostrano l'informativa come QR. Aggiunto anche il testo breve dell'informativa per Apple TV nei metadati.
- **Cache svuotata da tvOS mentre l'app è sospesa**: ora si ricontrolla al ritorno in primo piano e si torna alle foto demo con un messaggio.
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
- Gli indirizzi della Picker API valgono circa 60 minuti. L'app li rinnova richiedendo di nuovo l'elenco della sessione ogni 50 minuti, o subito dopo un 401/403. Le richieste parallele condividono un solo rinnovo.
- La sessione resta aperta finché Google la tiene valida (`expireTime`, circa una settimana). Alla scadenza l'app torna alle foto demo e chiede di scegliere di nuovo le foto. La scadenza della sessione appena sostituita non tocca la selezione nuova.
- “Disconnetti e cancella” cancella l'elenco, svuota la cache in RAM, chiude la sessione presso Google e revoca l'accesso.
- All'avvio si eliminano le foto scaricate dalle versioni precedenti.
- Il salvaschermo usa la stessa selezione, quindi richiede la connessione.
- Su Android la cache su disco di Coil è limitata a 32 MB e contiene solo le foto inviate con Google Cast.

Test JVM: 63, tutti superati, e la CI Android (build, test, Lint, APK) è verde. I test XCTest equivalenti sono stati aggiornati, ma vanno eseguiti su un Mac.

## Raccomandazioni aperte

| Priorità | Raccomandazione |
| --- | --- |
| Alta | Eseguire sulla TV i flussi modificati: collegamento Google (incluso annullamento e token revocato), USB con migliaia di foto, Indietro e barra dei comandi nello slideshow, Foto misteriosa su Android 9–11, salvaschermo, movimento lento e pausa automatica. |
| Bassa | I titoli predefiniti generati per le foto senza nome (“Foto N”) e i messaggi interni di log restano in italiano. |
| Bassa | Adottare `androidx.tv:tv-material` per componenti con focus e indicazioni TV native. |
| Bassa | Aggiungere un Web Receiver di fallback per dispositivi Cast senza l’app installata. |
