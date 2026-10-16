# Risposte per Google Play Data safety e App Store Privacy

Queste risposte descrivono la build attuale (Android 1.3.0, Apple TV 1.1.0). Vanno ricontrollate a ogni nuova versione e ogni volta che si aggiunge un SDK.

## Google Play – Sicurezza dei dati

Dalla versione 1.3.0 l'app per Android TV non include più Google Cast né altri SDK che inviano dati: le dipendenze sono AndroidX, Jetpack Compose, Coil, OkHttp, kotlinx.coroutines e ZXing, che lavorano solo sul dispositivo. Il codice dell'app non invia nulla allo sviluppatore. Google considera "raccolti" i dati trasmessi fuori dal dispositivo; qui non ce ne sono:

- **Google Foto**: le chiamate a Google partono su richiesta dell'utente per leggere il suo stesso account e mostrargli le foto scelte. Le foto passano da Google alla TV solo per essere mostrate, senza essere salvate.
- **Foto degli invitati**: vanno dal telefono dell'invitato alla TV attraverso la rete di casa, senza passare da internet né da server dello sviluppatore. Restano sulla TV solo durante la festa (in memoria, con una cartella temporanea di riserva) e vengono cancellate alla fine. Nome facoltativo dell'invitato: mostrato sulla TV accanto alle sue foto e cancellato con esse.
- **Token Google e password della Wi‑Fi** per il QR degli invitati: cifrati sul dispositivo, mai trasmessi allo sviluppatore.
- **Foto e musica del disco USB**: lette dal disco con i permessi di lettura di foto e audio, solo per mostrarle e riprodurle sulla TV. Non vengono copiate né trasmesse; sulla TV resta solo il percorso della cartella e del brano scelti.

| Domanda di Play Console | Risposta |
| --- | --- |
| L'app raccoglie o condivide uno dei tipi di dati richiesti? | **No** |
| Tutti i dati raccolti sono criptati in transito? | Non applicabile (nessun dato raccolto) |
| Gli utenti possono richiedere l'eliminazione dei dati? | Non applicabile. Sul dispositivo: "Disconnetti e cancella" per Google Foto, "Termina la festa e cancella le foto" per le foto degli invitati |
| Metodi di creazione account | **Nessun account**: l'app non crea account, e il login Google serve solo ad accedere a Google Foto |

Se il revisore chiede comunque di dichiarare le foto (di Google Foto o degli invitati), la risposta coerente è: *Foto e video › Foto: raccolta, non condivisa, elaborata solo temporaneamente, raccolta facoltativa, scopo Funzionalità dell'app*.

### Altre sezioni di Play Console

- **Accesso all'app**: “Prova subito” mostra tutte le funzioni senza login. Le foto degli invitati si provano con un telefono sulla stessa rete della TV, senza account. Per Google Foto fornisci un account Google di test e descrivi la procedura dei due QR. La schermata di consenso OAuth deve essere **In produzione**: in modalità test l'account andrebbe aggiunto agli utenti di test e i suoi token scadrebbero dopo 7 giorni.
- **Annunci**: l'app non contiene annunci.
- **ID pubblicità**: l'app non usa l'ID pubblicità. Rispondi **No** nella dichiarazione. Il manifest rimuove esplicitamente il permesso `com.google.android.gms.permission.AD_ID`, anche se una libreria lo aggiungesse.
- **Classificazione dei contenuti**: categoria Utilità/Produttività/Altro. Alla domanda sui contenuti generati dagli utenti: le foto degli invitati restano sulla TV di chi organizza la festa, non vengono pubblicate né condivise con altri utenti dell'app, e l'organizzatore può attivare l'approvazione prima che compaiano e cancellarle in ogni momento. Nessuna interazione tra utenti attraverso l'app.
- **Pubblico di destinazione**: 13+ o 18+. L'app non è progettata per bambini, e con fasce sotto i 13 anni si applicano i requisiti Families.
- **Android TV**: attiva la distribuzione su Android TV in *Impostazioni avanzate › Form factor* e carica banner TV e screenshot TV.

### Autorizzazioni per foto e video

L'app dichiara `READ_MEDIA_IMAGES` (Android 13 e successivi; `READ_EXTERNAL_STORAGE` fino ad Android 12) e `READ_MEDIA_AUDIO`. Play Console chiede di motivare l'accesso alle foto: la funzione principale dell'app è mostrare in slideshow intere cartelle di foto di un disco USB collegato alla TV, e molte TV non hanno il selettore di documenti né il selettore di foto di Android. Testo proposto:

> FotoFesta TV is a slideshow app for Android TV. Its core feature is showing every photo in a folder of a USB drive plugged into the TV, including subfolders, with the remote control. The app needs ongoing access, not a one-time selection: it reads the folder again at every start, so photos added later to the drive appear, and it shows the same folder as the system screensaver (DreamService), which runs without the app's interface. Many Android TV devices have no document picker or photo picker, so the app lists the mounted drives and reads the chosen folder directly. Photos are only displayed on the TV: they are not copied, uploaded or shared. The permission is requested only when the user opens the USB folder browser.

Il permesso si chiede solo all'apertura del browser delle cartelle; se viene negato, l'app usa il selettore di sistema quando la TV lo ha.

## App Store – Privacy dell'app (etichetta nutrizionale)

| Domanda di App Store Connect | Risposta |
| --- | --- |
| Raccogli dati da questa app? | **No, non raccogliamo dati** |

L'app per Apple TV non include SDK di terze parti. Apple considera raccolta la trasmissione di dati fuori dal dispositivo quando lo sviluppatore o i suoi partner possono accedervi più a lungo del tempo necessario a gestire la richiesta. Le foto scelte in Google Foto passano direttamente da Google al dispositivo solo per essere mostrate, senza essere salvate; le foto degli invitati vanno dal telefono alla Apple TV sulla rete di casa e vengono cancellate a fine festa. Il manifest `PrivacyInfo.xcprivacy` dichiara UserDefaults (CA92.1) e lo spazio libero su disco (E174.1, per il limite della cartella temporanea).

## Informativa privacy

Entrambi gli store chiedono l'URL dell'informativa. È in `site/privacy/index.html` e va pubblicata come descritto in [PUBLISHING.md](PUBLISHING.md#informativa-privacy-e-pagina-di-supporto) dopo aver completato i campi evidenziati.
