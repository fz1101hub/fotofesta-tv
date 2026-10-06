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

## Raccomandazioni aperte

| Priorità | Raccomandazione |
| --- | --- |
| Alta | Eseguire sulla TV i flussi modificati: collegamento Google (incluso annullamento e token revocato), USB con migliaia di foto, Indietro e barra dei comandi nello slideshow, Foto misteriosa su Android 9–11. |
| Media | Spostare i testi dell’interfaccia in `strings.xml` per abilitare traduzioni e la verifica di lint sui testi codificati. |
| Media | Aggiornare `versionCode`/`versionName` prima del prossimo rilascio. |
| Media | Valutare l’introduzione di Hilt o di una factory per iniettare repository e `OkHttpClient` nel ViewModel, così da testare anche i flussi Google con un server finto (es. MockWebServer). |
| Bassa | Adottare `androidx.tv:tv-material` per componenti con focus e indicazioni TV native. |
| Bassa | Aggiungere un Web Receiver di fallback per dispositivi Cast senza l’app installata. |
