# Audit indipendente del codice — FotoFesta TV

**Data:** 14 ottobre 2026  
**Ambito:** intero repository, con analisi approfondita dei sorgenti Android TV e Apple TV (tvOS).  
**Baseline:** ramo `fix/xiaomi-tv-startup-1.2.1` (include le modifiche Xiaomi 1.2.1, non ancora convalidate su dispositivo).  
**Autore della revisione:** analisi nuova a partire dal codice; `docs/AUDIT.md` è stato usato solo per confronto di copertura e **non** come prova di correttezza.

## 1. Sintesi esecutiva

**Esito: NON PRONTO per dichiarare risolto il problema Xiaomi né per una release priva di riserve.** Il codice presenta difetti identificabili nei percorsi di errore della cache Google Foto e nella gestione concorrente della versione tvOS. Non è emersa una prova che il filtro `LAUNCHER` sia la vera causa del ritorno alla Home su Xiaomi: `LEANBACK_LAUNCHER` è già un filtro valido su Android TV; la correzione 1.2.1 è una misura di compatibilità e diagnostica, ancora da provare.

**Risultati:** 2 problemi Android ad alto impatto potenziale sui dati locali; 1 rischio di memoria alto su Apple TV; 4 difetti logici/concorrenza tvOS di gravità media; altre criticità di sicurezza, UX e copertura di test. Non ho individuato un exploit critico dimostrato, né ho verificato una build completa su una TV.

L'audit NON modifica i sorgenti applicativi: questo documento registra risultati e interventi suggeriti, affinché le correzioni possano avere test di regressione dedicati.

## 2. Materiale e metodo

- Inventario GitHub del ramo: **201 file** (circa 44,5 MB inclusi asset binari); **36 Kotlin di produzione Android**, **19 Swift di produzione Apple**, **10 file di test Kotlin**, **3 file di test Swift**; Gradle, manifest, risorse XML, Swift Package/XcodeGen, Fastlane, workflow, script, traduzioni e documentazione.
- Lettura indipendente di percorsi di avvio, stato, navigazione, Cast, OAuth, importazione, persistenza, transizioni, download, salvaschermo e giochi. Tracciati separatamente i percorsi normali, di errore, annullamento, riavvio e concorrenza.
- Inventario dei test: **82 casi** dichiarati nei sorgenti (53 Android e 29 Swift), **non eseguiti** in questa revisione; assenti test strumentali di avvio Xiaomi e UI test tvOS.
- Controllo statico dei riferimenti alle risorse Android `R.string`, `R.plurals`, `R.drawable`, `R.mipmap`, `R.xml`: nessun riferimento irrisolto nei 36 file Kotlin; le 147 risorse testuali inglesi e le 146 italiane differiscono per `app_name`, intenzionalmente non traducibile.
- Eseguita una **riproduzione isolata in Swift** dell'algoritmo di `PlaybackOrder` con rimescolatore deterministico: visitate `0, 1, 2`, il successivo ciclo parte da `1`, ma `previous(before: 1)` restituisce `0` invece della foto precedentemente mostrata `2`. È un test isolato dell'algoritmo riportato nel sorgente, NON l'esecuzione del pacchetto originale.
- La dimensione dei file multimediali è verificata solo dai metadati GitHub, non con un controllo byte-per-byte di ogni asset.

### Limiti di verifica

- Nell'ambiente disponibile non sono installati Android SDK e Gradle: **nessuna build APK, nessun Android Lint, nessun test strumentale, nessuna firma APK verificata**.
- Il compilatore Swift presente nell'ambiente non è un ambiente macOS/tvOS Xcode: **nessuna build tvOS, XCTest del pacchetto originale o prova su Apple TV**.
- Le GitHub Actions associate al repository erano prive di runner/esecuzioni utilizzabili in precedenza. Non sono una prova di build per il ramo 1.2.1.
- Non eseguiti test su TV Xiaomi, Apple TV, rete Cast reale, Google OAuth del proprietario né valutazione CVE/SCA completa delle dipendenze. Nessuna attestazione di sicurezza o certificazione formale.

## 3. Risultati — Android TV

### AND-01 — Alta — Migrazione della cache può eliminare le foto sorgente in caso di errore

**File:** [GooglePhotosPickerRepository.kt, righe 216–223](../app/src/main/java/it/fotofesta/tv/google/GooglePhotosPickerRepository.kt#L216-L223).

`adoptFrom(legacy)` tenta `source.renameTo(cacheDirectory)` senza controllare il risultato; poi esegue **sempre** `legacy.clearCache()`. Se `renameTo` fallisce (permessi, filesystem, errore I/O, percorso non disponibile), la cache precedente viene cancellata nonostante la migrazione non riuscita. È un difetto certo del percorso d'errore, anche se la frequenza su dispositivi reali non è misurata.

**Correzione:** cancellare la cache vecchia solo dopo migrazione comprovata; propagare/registrare l'errore; verificare integrità e presenza del manifest; aggiungere test con fallimento iniettato della rinomina.

### AND-02 — Alta — Il ripristino fallito di una raccolta può essere eliminato all'avvio seguente

**File:** [GooglePhotosPickerRepository.kt, righe 160–168 e 225–230](../app/src/main/java/it/fotofesta/tv/google/GooglePhotosPickerRepository.kt#L160-L230).

Dopo aver rinominato la cache attiva in `google_photos.old-*`, se il passaggio della staging a cache attiva fallisce si tenta di ripristinare `previous.renameTo(cacheDirectory)`, ma **non si verifica il risultato**. A un successivo avvio, `deleteLeftovers()` elimina indiscriminatamente le cartelle `old-*`, anche quando sono l'unica copia valida. È un percorso possibile di perdita dati dopo errore/ripristino fallito.

**Correzione:** all'avvio recuperare la più recente `old-*` valida se manca la cache attiva, eliminare i backup solo dopo aver verificato il ripristino, introdurre protocollo di commit/ripristino esplicito e test di fault injection.

### AND-03 — Media — Limiti incompleti ai download e alle risorse Cast

**File:** [GooglePhotosPickerRepository.kt, righe 176–201](../app/src/main/java/it/fotofesta/tv/google/GooglePhotosPickerRepository.kt#L176-L201), [RemoteUrlPolicy.kt](../app/src/main/java/it/fotofesta/tv/cast/RemoteUrlPolicy.kt).

I download Google controllano lo spazio prima dell'operazione, ma il corpo HTTP viene copiato fino a EOF senza un limite per file né dimensione di risposta; quattro download concorrenti possono consumare rapidamente spazio. Il filtro Cast verifica l'host **sintatticamente**, senza controllo dell'indirizzo DNS effettivamente raggiunto; lo stesso limite è già riconosciuto nel documento di protocollo (DNS rebinding). Nessun SSRF effettivo è stato dimostrato.

**Correzione:** limite dimensione/download, tipo MIME e risposta effettivi, soglie di spazio con margini e cancellazione file parziali; per URL Cast controlli DNS/redirect/rete sul client che recupera i dati, non solo sulla stringa.

### AND-04 — Media — La diagnostica Xiaomi conserva e visualizza messaggi d'eccezione integrali

**File:** [StartupDiagnostics.kt, righe 24–49](../app/src/main/java/it/fotofesta/tv/StartupDiagnostics.kt#L24-L49).

La versione 1.2.1 salva fino a 12.000 caratteri di `error.message` e stacktrace in SharedPreferences e li mostra sulla TV al riavvio. Un messaggio d'errore proveniente da librerie di rete potrebbe contenere URL, identificativi o informazioni personali. Non è provata una perdita di token; la superficie di divulgazione locale è concreta.

**Correzione:** mostrare principalmente tipo di eccezione, classe/metodo, prime righe e identificativo del crash; eliminare/redigere credenziali, Authorization e query sensibili; offrire dettaglio solo dietro scelta esplicita. Non inviare log in rete automaticamente.

### AND-05 — Blocco di convalida — Il crash Xiaomi non è ancora diagnosticato

**File:** [AndroidManifest.xml](../app/src/main/AndroidManifest.xml), [MainActivity.kt](../app/src/main/java/it/fotofesta/tv/MainActivity.kt), [FotoFestaApplication.kt](../app/src/main/java/it/fotofesta/tv/FotoFestaApplication.kt).

Il manifest contiene ora sia `LEANBACK_LAUNCHER` che `LAUNCHER`, mentre MediaSession/Cast sono gestiti come componenti facoltativi; il catcher di eccezioni può dare un'indicazione al secondo avvio. Non esiste tuttavia il log originale della Xiaomi. Il nuovo manifest non dimostra né esclude un crash in `ViewModel`, Compose, Play Services o codice nativo. Non considerare il problema risolto prima di prova manuale e acquisizione crash.

**Correzione/test:** build effettiva `assembleDebug`, installazione pulita, prima/seconda apertura da Home e Impostazioni, screenshot della schermata diagnostica; aggiungere un test strumentale di `MainActivity` e uno smoke test su Google TV.

## 4. Risultati — Apple TV/tvOS

### TVO-01 — Alta — Download di foto 4K caricati integralmente in RAM

**File:** [PhotosPicker.swift, righe 138–151 e 204–224](../apple/FotoFestaCore/Sources/FotoFestaCore/PhotosPicker.swift#L138-L224), [HTTP.swift](../apple/FotoFestaCore/Sources/FotoFestaCore/HTTP.swift).

`URLSessionHTTPClient.send` usa `URLSession.data(for:)`, restituendo l'intero payload come `Data`; `downloadOne` lo scrive poi su disco. Quattro scaricamenti 4K in parallelo non hanno un limite per dimensione. Questo crea un rischio concreto di picchi di memoria e terminazione dell'app su dispositivi con RAM limitata. Non è stato misurato un crash effettivo su Apple TV.

**Correzione:** streaming su file tramite `URLSession.download(for:)` (o API equivalente), trasferimento atomico, limite per risorsa, cancellazione e gestione della memoria; test con file grandi.

### TVO-02 — Media — Annullamento download ritardato e inutili richieste successive

**File:** [PhotosPicker.swift, righe 138–154 e 204–223](../apple/FotoFestaCore/Sources/FotoFestaCore/PhotosPicker.swift#L138-L223).

Il gruppo di task continua a inserire i successivi elementi anche dopo annullamento del task padre; `downloadOne` cattura ogni `Error` (anche `CancellationError`) e restituisce `nil`. `Task.checkCancellation()` viene chiamato solo quando il gruppo ha già terminato. Su raccolte grandi, la procedura può continuare molto oltre la pressione di «Indietro».

**Correzione:** `Task.checkCancellation()` prima di aggiungere nuovi task e tra i batch; propagare `CancellationError` invece di trasformarlo in errore della singola foto; usare throwing task group ove utile.

### TVO-03 — Media — «Precedente» errato al cambio di ciclo casuale

**File:** [PlaybackOrder.swift, righe 30–68](../apple/FotoFestaCore/Sources/FotoFestaCore/PlaybackOrder.swift#L30-L68).

A fine ciclo casuale `reshuffle` sostituisce l'ordine; `previous(before:)` torna all'ultimo elemento del **nuovo** ordine, non necessariamente all'ultima foto del ciclo precedente. Caso isolato riprodotto con ordine `0,1,2`, nuovo ciclo `1,...,0`: l'utente ha appena visto `2`, ma «Precedente» torna a `0`. La versione Android gestisce invece `lastOfPreviousCycle`.

**Correzione:** salvare l'indice dell'ultimo elemento del ciclo precedente e gestire il passaggio inverso; introdurre il test `previousAfterReshuffleReturnsPreviouslyShownPhoto`.

### TVO-04 — Media — Un controllo ritardato del Memory può modificare una nuova partita

**File:** [GamesView.swift, righe 104–128](../apple/FotoFestaTV/Sources/Views/GamesView.swift#L104-L128).

Il `deck` di controllo è `cards.map(\.id)`, ma `newGame()` assegna ogni volta ID sequenziali `0,1,...`. La verifica del vecchio task, in attesa 450–900 ms, può quindi risultare vera anche dopo «Nuova partita» e applicare a nuove carte gli indici della partita precedente.

**Correzione:** identificatore/generazione della partita che cambi a ogni reset, cancellazione del task pendente e confronto della generazione prima di mutare lo stato.

### TVO-05 — Media — Disconnessione Google e nuovi import possono modificare la stessa cache in parallelo

**File:** [AppModel.swift, righe 187–202](../apple/FotoFestaTV/Sources/AppModel.swift#L187-L202).

`disconnectGoogle()` crea un `Task.detached` che esegue `picker.clearCache()`, senza attendere o coordinarsi con il flusso di importazione. L'utente può avviare un nuovo import mentre la cancellazione precedente è ancora in corso. `PhotosPicker` è `@unchecked Sendable` e la gestione delle directory non è serializzata: esiste una corsa potenziale che può cancellare la nuova raccolta.

**Correzione:** serializzare modifiche alla cache in un actor dedicato, attendere la disconnessione o impedire un nuovo import fino al completamento; test di concorrenza.

### TVO-06 — Media — Backup della cache non recuperato dopo interruzione nel commit

**File:** [PhotosPicker.swift, righe 162–179](../apple/FotoFestaCore/Sources/FotoFestaCore/PhotosPicker.swift#L162-L179).

La cache attuale viene spostata in una cartella `old-*`, seguita da una rinomina della cartella staging. Se il processo viene terminato fra queste due operazioni, il successivo `cachedPhotos()` legge solo la directory attiva e non recupera automaticamente il backup ancora presente.

**Correzione:** routine di recupero all'avvio che valida e ripristina una copia `old-*` prima di presentare foto demo; pulizia dei backup dopo commit verificato.

### TVO-07 — Bassa — Possibile flash della fotografia precedente nel caricamento asincrono

**File:** [ImageLoader.swift, righe 52–84](../apple/FotoFestaTV/Sources/ImageLoader.swift#L52-L84).

`PhotoImage` conserva `@State image` mentre il task relativo a una nuova foto viene eseguito. Il contenuto precedente può rimanere visibile fino alla fine del caricamento.

**Correzione:** azzerare lo stato al cambio di identificativo prima della richiesta, o legare lo stato visualizzato a `photo.id`.

## 5. Qualità, test, privacy, distribuzione

**P-01 — Release blocker:** 82 test dichiarati, ma nessuno eseguito qui e nessun test di avvio Xiaomi, UI tvOS, salvaschermo o interruzione di transazione cache. I test di successo della migrazione non coprono `renameTo=false` né il ripristino fallito.

**P-02 — Release blocker:** mancano build effettive `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`, `cd apple/FotoFestaCore && swift test` sul Mac, e compilazione tvOS con XcodeGen/Xcode. Il rapporto `docs/VALIDATION.md` riguarda la versione **1.0.0**, quindi non valida la 1.2.1.

**P-03 — Sicurezza residua:** il filtro Cast degli URL HTTPS è utile e testa molte codifiche di indirizzi locali, ma manca mitigazione network-layer per DNS rebinding e redirect; i token Google sono protetti da Android Keystore/Keychain e i backup Android escludono `google_tokens`. Non risultano segreti reali hard-coded nei file esaminati; non è stata eseguita una scansione storica dei commit né un vulnerability scan delle dipendenze.

**P-04 — Trasparenza:** l'interfaccia OAuth Google dipende da `PHOTOS_CLIENT_ID`/`PHOTOS_CLIENT_SECRET`, volutamente vuoti nel repository. Un APK compilato senza configurazione Google Foto non potrà collegare un account, anche se deve comunque aprire la Home e le foto demo.

**P-05 — CI/artefatti:** la presenza dei workflow `android.yml` e `release.yml` non costituisce prova di esecuzione con successo; i runner GitHub Actions erano già indisponibili. Non dichiarare superati test/lint/build senza log o APK verificabile.

### Aspetti positivi verificati staticamente

- Manifest Android con attività esportata per TV, banner e icona; nessuna risorsa Android riferita ma inesistente nel codice controllato.
- Preferenze e token Google separati; scope OAuth limitato alle foto selezionate, no trasferimento di token nel protocollo Cast; accesso USB tramite selettore documenti del sistema.
- Test di parsing Cast, paginazione Picker, refresh token, cache nominale, URL remoti, animazioni e shuffle presenti su entrambi i linguaggi.
- Le impostazioni sono salvate localmente; niente SDK pubblicitari o analytics rilevati nei moduli applicativi esaminati.

## 6. Interventi prioritari e piano di verifica

| Priorità | Modifica | Prova minima richiesta |
| --- | --- | --- |
| P0 | AND-01 e AND-02: migrazione/rollback cache Android senza perdita dati | Test con `renameTo` fallita e riavvio durante commit |
| P0 | Xiaomi: build 1.2.1 e analisi dell'errore reale | Avvio da Home e App, due tentativi, screenshot del crash nativo |
| P1 | TVO-01 e TVO-02: download Apple a streaming e annullamento rapido | File grandi, annullamento su 500 foto, memoria misurata |
| P1 | TVO-03 e TVO-04: precedente in shuffle e generazione partita | Test deterministici automatizzati |
| P1 | TVO-05 e TVO-06: serializzazione e ripristino cache Apple | Disconnetti/reimporta simultanei e crash in mezzo al commit |
| P1 | AND-03/AND-04: limiti download, Cast e protezione log diagnostici | Test MIME, dimensione, redirect, privacy dei messaggi |
| P2 | TVO-07: reset caricamento immagine | Scorrimento rapido con I/O lento |
| Gate | Test automatici + collaudo device Android TV e Apple TV | Report Gradle/Xcode e almeno una sessione end-to-end reale |

### Sequenza suggerita

1. Eseguire **test di regressione scritti prima delle correzioni** per i percorsi perdita dati.
2. Correggere Android e tvOS in commit separati e sottoporre a code review.
3. Generare build reali, verificare installazione/firma e aprire l'app su Xiaomi.
4. Ripetere casi Google Foto (token revocato, rete interrotta, cache piena, migrazione), Cast (URL/redirect), 1.000 foto USB, salvaschermo, Memory e shuffle.
5. Solo dopo i risultati aggiornare lo stato da «in verifica» a «validato».

## 7. Conclusione

L'audit è **indipendente dal rapporto storico**, approfondito sui sorgenti e completo come **ispezione statica delle aree applicative**, non come certificazione runtime: alcune anomalie sono dimostrate logicamente, altre richiedono simulazioni d'errore o hardware reale. Questa distinzione è intenzionale. Il rapporto individua correzioni concrete, ma **non autorizza ancora a dichiarare funzionante l'APK Xiaomi 1.2.1**.
