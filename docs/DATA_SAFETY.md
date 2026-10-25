# Risposte per Google Play Data safety e App Store Privacy

Queste risposte descrivono la build attuale (Android 1.4.0, Apple TV 1.2.0). Vanno ricontrollate a ogni nuova versione e ogni volta che si aggiunge un SDK.

## Google Play – Sicurezza dei dati

Dalla versione 1.3.0 l'app per Android TV non include più Google Cast né altri SDK che inviano dati: le dipendenze sono AndroidX, Jetpack Compose, Media3 (lettore video), Coil, OkHttp, kotlinx.coroutines, ZXing e, per le cartelle condivise, smbj, dcerpc e Bouncy Castle, che lavorano solo sul dispositivo o verso il computer scelto dall'utente. Il codice dell'app non invia nulla allo sviluppatore. Google considera "raccolti" i dati trasmessi fuori dal dispositivo; qui non ce ne sono:

- **Google Foto**: le chiamate a Google partono su richiesta dell'utente per leggere il suo stesso account e mostrargli le foto scelte. Le foto passano da Google alla TV solo per essere mostrate, senza essere salvate.
- **Foto degli invitati**: vanno dal telefono dell'invitato alla TV attraverso la rete di casa, senza passare da internet né da server dello sviluppatore. Restano sulla TV solo durante la festa (in memoria, con una cartella temporanea di riserva) e vengono cancellate alla fine. Nome facoltativo dell'invitato: mostrato sulla TV accanto alle sue foto e cancellato con esse.
- **Token Google, password della Wi‑Fi** per il QR degli invitati **e password delle cartelle condivise**: cifrati sul dispositivo, mai trasmessi allo sviluppatore. La password delle cartelle condivise va solo al computer o al NAS scelto, nell'accesso SMB (NTLM), sulla rete di casa.
- **Foto, video e musica del disco USB**: letti dal disco con i permessi di lettura di foto, video e audio, solo per mostrarli e riprodurli sulla TV. Non vengono copiati né trasmessi; sulla TV resta solo il percorso della cartella e del brano scelti.
- **Cartelle della rete di casa** (server multimediali DLNA di NAS, router, PC): la TV cerca i server sulla rete locale e legge foto e video dal server scelto solo per mostrarli, senza copiarli e senza passare da internet. Sulla TV restano il nome della cartella e l'identificativo del server.
- **Cartelle condivise** (SMB di PC, Mac e NAS): la TV cerca i computer sulla rete locale (Bonjour) e legge foto e video dalla cartella scelta solo per mostrarli, senza copiarli e senza passare da internet. Sulla TV restano l'indirizzo del computer, il nome utente, la cartella e la password cifrata, nel file escluso dal backup.
- **Album condiviso di iCloud**: su richiesta dell'utente la TV chiede ad Apple l'elenco dell'album pubblico indicato dal link e scarica le foto da Apple solo per mostrarle. Il codice del link va ad Apple, che lo gestisce già perché è un album pubblico; allo sviluppatore non arriva nulla. Il link arriva dal telefono attraverso una pagina servita dalla TV sulla rete di casa. Sulla TV restano il codice e il nome dell'album.
- **Video di Google Foto**: letti da Google come le foto, solo per mostrarli, senza copie sulla TV.
- **Gioco con i telefoni**: durante la festa gli invitati rispondono dalla pagina servita dalla TV; risposte, nome facoltativo e punti restano in memoria sulla TV e spariscono alla fine del gioco o della festa.
- **Reazioni, dediche e sondaggi**: le emoji, le dediche (al massimo 160 caratteri) e i voti mandati dalla pagina della festa restano in memoria sulla TV, servono solo a mostrarli sullo schermo, al libro degli ospiti, al premio "Foto della serata" e all'estrazione a premi, e vengono cancellati con "Termina la festa". Con l'approvazione attiva le dediche compaiono solo dopo il via libera dell'organizzatore.
- **Ripresa della festa e regia**: con "Riprendi la festa dopo un'interruzione" (attiva di serie) le foto degli invitati, le dediche, i nomi, le reazioni per foto e gli scatti usati vengono scritti subito nella memoria privata dell'app, solo per riprendere la festa se l'app si chiude; si cancellano con "Termina la festa". Il telefono manda un identificativo casuale (non legato alla persona) che serve a contare voti, scatti a testa e il regista di turno. Il PIN della regia cambia a ogni festa e non lascia la TV.
- **Video, ricevute e album degli invitati**: i video brevi degli invitati restano sulla TV come le foto (cartella privata dell'app) e si cancellano con "Termina la festa". La ricevuta per togliere una foto è un codice calcolato dalla TV e tenuto solo sul telefono dell'invitato. L'archivio da portare a casa, se l'organizzatore lo permette, contiene solo foto e video approvati ed è servito dalla TV sulla rete di casa.
- **festa.local e codice di quattro cifre**: durante la festa la TV risponde in mDNS al solo nome "festa.local" sulla rete locale; nessun dato esce dalla rete di casa.
- **Preparazione della festa e giochi**: logo, sponsor, scaletta, invitati attesi, domande del quiz e foto da piccoli con il nome arrivano dalla regia del telefono e restano nella memoria privata dell'app finché l'organizzatore non li toglie. Le foto da piccoli e la lista degli invitati non vanno mai ai telefoni degli ospiti. L'esportazione sulla chiavetta (`FotoFesta/fotofesta-config.json`) contiene anche la password della Wi‑Fi degli invitati, in chiaro: va scritta solo su una chiavetta di chi organizza.
- **Annunci letti ad alta voce e chiosco**: la sintesi vocale è quella del televisore; il PIN del chiosco è salvato solo come impronta SHA-256 con sale.
- **Dispositivi della festa (ondata 12)**: luci Hue, WLED e Nanoleaf e stampanti (IPP, ESC/POS) si cercano con DNS-SD sulla rete locale e ricevono solo comandi di colore, foto da stampare e scontrini, solo su indirizzi privati. Le chiavi di abbinamento delle luci sono cifrate con Android Keystore. Il server FTP per le fotocamere (porta 2121) è attivo solo durante la festa e con l'impostazione accesa, accetta solo indirizzi della rete locale, chiede utente e password a sei cifre e riceve solo file, senza esporre file della TV. La fotocamera USB (PTP/MTP) è letta solo con il permesso USB concesso dall'utente, e solo per gli scatti fatti dopo il collegamento. La copia sulla cartella di rete (SMB) scrive le foto degli invitati nel computer scelto dall'utente come sorgente, in `FotoFesta-copie`. Con "Festa senza tracce" non ci sono copie, stampe né scontrini.
- **Foto degli invitati sulla chiavetta (facoltativa)**: la cartella privata dell'app sul supporto rimovibile; si cancella come la memoria della TV a fine festa.
- **Canale della TV (solo Google Play)**: il permesso `WRITE_EPG_DATA` serve solo a scrivere il canale "FotoFesta" dell'app nella guida TV; le foto del canale si disegnano sulla TV e non escono dal dispositivo.
- **Copia nella galleria (facoltativa, spenta di serie)**: su Android TV 10 e successivi, e sull'app Mac, le foto degli invitati possono essere copiate in Immagini/FotoFesta sul dispositivo; quelle copie restano dopo la festa e si cancellano dalla galleria del dispositivo. Con "Festa senza tracce" la copia non si fa e ogni foto si cancella dopo essere stata mostrata una volta.

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
- **Classificazione dei contenuti**: categoria Utilità/Produttività/Altro. Alla domanda sui contenuti generati dagli utenti: le foto e le dediche degli invitati restano sulla TV di chi organizza la festa, non vengono pubblicate né condivise con altri utenti dell'app, e l'organizzatore può attivare l'approvazione prima che compaiano e cancellarle in ogni momento. Nessuna interazione tra utenti attraverso l'app.
- **Pubblico di destinazione**: 13+ o 18+. L'app non è progettata per bambini, e con fasce sotto i 13 anni si applicano i requisiti Families.
- **Android TV**: attiva la distribuzione su Android TV in *Impostazioni avanzate › Form factor* e carica banner TV e screenshot TV.

### Autorizzazioni per foto e video

L'app dichiara `READ_MEDIA_IMAGES` e `READ_MEDIA_VIDEO` (Android 13 e successivi; `READ_EXTERNAL_STORAGE` fino ad Android 12) e `READ_MEDIA_AUDIO`. Play Console chiede di motivare l'accesso a foto e video: la funzione principale dell'app è mostrare in slideshow intere cartelle di foto e video di un disco USB collegato alla TV, e molte TV non hanno il selettore di documenti né il selettore di foto di Android. Testo proposto:

> FotoFesta TV is a slideshow app for Android TV. Its core feature is showing every photo and video in a folder of a USB drive plugged into the TV, including subfolders, with the remote control. The app needs ongoing access, not a one-time selection: it reads the folder again at every start, so photos and videos added later to the drive appear, and it shows the same folder as the system screensaver (DreamService), which runs without the app's interface. Many Android TV devices have no document picker or photo picker, so the app lists the mounted drives and reads the chosen folder directly. Photos and videos are only displayed on the TV: they are not copied, uploaded or shared. The permission is requested only when the user opens the folder browser.

Il permesso si chiede solo all'apertura del browser delle cartelle; se viene negato, il browser mostra comunque i server della rete di casa e, quando la TV lo ha, resta il selettore di sistema. Senza il permesso dei video (concesso a parte su alcune versioni di Android) le cartelle USB mostrano solo le foto.

### Autorizzazioni per la rete della TV, DLNA e avvio automatico

- `NEARBY_WIFI_DEVICES` (Android 13+, con `neverForLocation`) e `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` solo fino ad Android 12 (`maxSdkVersion="32"`): servono soltanto a `startLocalOnlyHotspot`, la rete Wi‑Fi creata dalla TV per le feste senza rete di casa. L'app non legge la posizione: nella sezione *Sicurezza dei dati* la posizione resta **non raccolta**. Si chiedono solo quando si preme “Crea la rete della TV”. I `uses-feature` di Wi‑Fi e posizione sono `required="false"`, così le TV solo via cavo o senza GPS restano compatibili.
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`: hotspot locale e annunci SSDP dello schermo DLNA (spento di default). Le foto ricevute via DLNA si scaricano solo da indirizzi privati della rete locale.
- `RECEIVE_BOOT_COMPLETED`: “Avvio con la TV”, spento di default; il ricevitore non fa nulla se l'impostazione è spenta.
- Telecamera e applausometro con un vecchio telefono: l'app legge in HTTP il flusso MJPEG e l'audio WAV dell'app “IP Webcam” solo su indirizzi della rete locale; non registra l'audio e nessun dato esce dalla rete di casa. Non servono permessi.
- Copia sulla chiavetta: cartella scelta dall'utente con il selettore di documenti (permesso persistente sull'URI), nessun permesso aggiuntivo.
- Aggiornamenti dal Play Store: libreria Play In-App Updates, nessun dato raccolto dall'app.
- Schermi aggiuntivi: durante la festa la TV si annuncia sulla rete locale con DNS-SD (`_fotofesta._tcp`, solo nome del dispositivo e porta); un'altra TV vi accede solo con il PIN della regia. Nessun permesso aggiuntivo.
- Parete delle foto e muro di telefoni: mostrano solo foto degli invitati già approvate, servite con la chiave della festa; mai la raccolta della TV (USB, Google Foto, rete).
- Silent disco: i telefoni con la chiave della festa possono scaricare solo il brano che la TV sta suonando, indicato da un'impronta del suo indirizzo; nessun altro file è raggiungibile.
- Capsula del tempo: file scritti solo nella cartella della chiavetta scelta dall'utente; non disponibile con “Festa senza tracce”.
- Slideshow a ritmo: la TV decodifica in memoria i primi 45 secondi dei brani scelti per calcolarne il battito; non registra audio e non usa il microfono.
- `android.hardware.usb.host` (non obbligatorio): fotocamera collegata con il cavo USB; il permesso per il singolo dispositivo lo concede l'utente nella finestra di sistema.
- `com.android.providers.tv.permission.WRITE_EPG_DATA` (solo versione Google Play): canale "FotoFesta" nella guida TV, con il servizio `TvInputService` legato a `BIND_TV_INPUT`.

## Amazon Appstore – Privacy e contenuti (Fire TV)

La versione per Fire TV raccoglie gli stessi dati della versione per Google Play, cioè nessuno: niente pubblicità, analisi o SDK che inviano dati, nessun account FotoFesta. Nel questionario di Amazon:
- **Raccolta di dati personali**: No. Le foto e i nomi degli invitati restano sulla TV e si cancellano a fine festa; Google Foto è un collegamento facoltativo dell'utente con il suo account Google.
- **Pubblicità e acquisti in app**: No.
- **Informativa privacy**: lo stesso URL di Google Play (`site/privacy/`), che cita anche Fire TV.
- La versione `amazon` non contiene la libreria Play In-App Updates.

## App Store – Privacy dell'app (etichetta nutrizionale)

| Domanda di App Store Connect | Risposta |
| --- | --- |
| Raccogli dati da questa app? | **No, non raccogliamo dati** |

L'app per Apple TV non include SDK di terze parti. Le **Foto di iCloud** si leggono dalla libreria Foto del dispositivo con PhotoKit, dopo il permesso di tvOS, solo per mostrarle: non vengono copiate né inviate; in UserDefaults resta l'identificativo dell'album scelto. Le **cartelle di rete** si leggono dai server della rete di casa, senza passare da internet. Apple considera raccolta la trasmissione di dati fuori dal dispositivo quando lo sviluppatore o i suoi partner possono accedervi più a lungo del tempo necessario a gestire la richiesta. Le foto e i video scelti in Google Foto passano direttamente da Google al dispositivo solo per essere mostrati, senza essere salvati; le foto degli invitati vanno dal telefono alla Apple TV sulla rete di casa e vengono cancellate a fine festa. Il manifest `PrivacyInfo.xcprivacy` dichiara UserDefaults (CA92.1) e lo spazio libero su disco (E174.1, per il limite della cartella temporanea).

## Informativa privacy

Entrambi gli store chiedono l'URL dell'informativa. È in `site/privacy/index.html` e va pubblicata come descritto in [PUBLISHING.md](PUBLISHING.md#informativa-privacy-e-pagina-di-supporto) dopo aver completato i campi evidenziati.
