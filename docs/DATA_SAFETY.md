# Risposte per Google Play Data safety e App Store Privacy

Queste risposte descrivono la build attuale (Android 1.2.0, Apple TV 1.0). Vanno ricontrollate a ogni nuova versione e ogni volta che si aggiunge un SDK.

## Google Play – Sicurezza dei dati

Il codice dell'app non invia nulla allo sviluppatore. Le foto e i token restano sul dispositivo. Le chiamate a Google Foto partono su richiesta dell'utente per leggere il suo stesso account, quindi non contano come raccolta.

L'app per Android TV include però il **Cast SDK** di Google (`play-services-cast-tv`, `play-services-cast`), che serve a ricevere i comandi da un telefono. Google dichiara che questo SDK invia dati anonimi di utilizzo e diagnostica, cifrati in transito, che né lo sviluppatore né l'utente possono disattivare o cancellare. Per questo il modulo va compilato con **Sì, raccoglie dati**.

Prima di inviare il modulo, apri la pagina ufficiale [Cast SDK Google Play Data Safety Section](https://developers.google.com/cast/docs/android_sender/data_disclosure) e riporta i tipi di dati elencati per il Cast TV SDK. Qui sotto c'è la compilazione attesa, da confermare con quella pagina, che Google aggiorna a ogni versione dell'SDK.

| Domanda di Play Console | Risposta |
| --- | --- |
| L'app raccoglie o condivide uno dei tipi di dati richiesti? | **Sì** (solo tramite il Cast SDK) |
| Tutti i dati raccolti sono criptati in transito? | Sì |
| Gli utenti possono richiedere l'eliminazione dei dati? | No: Google non consente di eliminare i dati raccolti dal Cast SDK. Le foto e i token si cancellano dall'app con “Disconnetti e cancella” |
| Metodi di creazione account | **Nessun account**: l'app non crea account, e il login Google serve solo ad accedere a Google Foto |

Tipi di dati attesi, tutti **raccolti, non condivisi, raccolta obbligatoria** (non disattivabile), elaborazione non temporanea, scopo **Analisi**:

| Categoria | Tipo di dati |
| --- | --- |
| Attività nell'app | Interazioni con l'app |
| Informazioni e prestazioni dell'app | Log degli arresti anomali, Diagnostica |
| ID dispositivo o altri ID | ID dispositivo o altri ID |

Se la pagina di Google indica categorie diverse, vale quella. Se un giorno l'app non avrà più bisogno di Google Cast, togliendo le due dipendenze da `app/build.gradle.kts` (insieme a `cast/` e `CastReceiverOptionsProvider`) si potrà rispondere **No**.

Foto e token non vanno dichiarati: restano sul dispositivo e non vengono trasmessi né allo sviluppatore né a terzi. Se il revisore chiede comunque di dichiarare le foto scaricate da Google Foto, la risposta coerente è: *Foto e video › Foto — raccolta, non condivisa, raccolta facoltativa, scopo Funzionalità dell'app*.

### Altre sezioni di Play Console

- **Accesso all'app**: “Prova subito” mostra tutte le funzioni senza login. Per Google Foto fornisci un account Google di test, aggiunto agli utenti di test della schermata di consenso OAuth, e descrivi la procedura dei due QR.
- **Annunci**: l'app non contiene annunci.
- **ID pubblicità**: l'app non usa l'ID pubblicità. Rispondi **No** nella dichiarazione. Il manifest rimuove esplicitamente il permesso `com.google.android.gms.permission.AD_ID`, anche se una libreria lo aggiungesse.
- **Classificazione dei contenuti**: categoria Utilità/Produttività/Altro. Nessun contenuto generato da altri utenti condiviso, nessuna interazione tra utenti.
- **Pubblico di destinazione**: 13+ o 18+. L'app non è progettata per bambini, e con fasce sotto i 13 anni si applicano i requisiti Families.
- **Android TV**: attiva la distribuzione su Android TV in *Impostazioni avanzate › Form factor* e carica banner TV e screenshot TV.

## App Store – Privacy dell'app (etichetta nutrizionale)

| Domanda di App Store Connect | Risposta |
| --- | --- |
| Raccogli dati da questa app? | **No, non raccogliamo dati** |

L'app per Apple TV non include Google Cast né SDK di terze parti. Apple considera raccolta la trasmissione di dati fuori dal dispositivo quando lo sviluppatore o i suoi partner possono accedervi più a lungo del tempo necessario a gestire la richiesta. Le foto scelte in Google Foto passano direttamente da Google al dispositivo e restano lì.

## Informativa privacy

Entrambi gli store chiedono l'URL dell'informativa. È in `site/privacy/index.html` e va pubblicata come descritto in [PUBLISHING.md](PUBLISHING.md#informativa-privacy-e-pagina-di-supporto) dopo aver completato i campi evidenziati.
