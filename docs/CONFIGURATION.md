# Configurazione di FotoFesta TV

Questa guida porta il progetto dalla build dimostrativa a una versione collegata al proprio account Google Photos API e spiega come funzionano le foto degli invitati.

## 1. Prerequisiti locali

Il progetto richiede JDK 17 e Android SDK. Lo script installa Platform 35 e i Build Tools necessari nella home dell’utente.

```bash
cd /percorso/fotofesta-tv
./scripts/setup_android_sdk.sh
```

La compilazione completa esegue test, lint, APK debug e AAB release:

```bash
./scripts/build.sh
```

Gli artefatti vengono copiati in `dist/` e accompagnati da checksum SHA-256.

## 2. Configurare Google Photos Picker API

Dal 31 marzo 2025 un’app di terze parti non può più leggere indiscriminatamente la libreria Google Foto. La soluzione ufficiale è la Picker API, con selezione esplicita dei contenuti da parte dell’utente.[1]

1. Apri [Google Cloud Console](https://console.cloud.google.com/) con il tuo account Google e crea un progetto, ad esempio "FotoFesta TV".
2. In **API e servizi › Libreria** cerca **Photos Picker API** e premi **Abilita**.
3. Apri **Google Auth Platform** (la vecchia "Schermata consenso OAuth") e premi **Inizia**:
   - nome dell'app "FotoFesta TV" ed email di assistenza;
   - pubblico **Esterno**;
   - email di contatto dello sviluppatore.
4. In **Google Auth Platform › Pubblico**, sotto **Utenti di test**, aggiungi gli account Google con cui proverai l'app. Finché l'app è in modalità test possono collegarsi solo questi account, e il collegamento scade dopo 7 giorni.
5. In **Google Auth Platform › Accesso ai dati** premi **Aggiungi o rimuovi ambiti**, inserisci a mano `https://www.googleapis.com/auth/photospicker.mediaitems.readonly` e salva.
6. Pubblica il sito della cartella `site/` (vedi [PUBLISHING.md](PUBLISHING.md)): la pagina `site/google/` è il punto in cui Google rimanda il telefono dopo il consenso, e deve essere raggiungibile in https. Con GitHub Pages l'indirizzo è `https://<utente>.github.io/fotofesta-tv/google/`.
7. In **Google Auth Platform › Client** premi **Crea client**, scegli il tipo **Applicazione web**, dai un nome e, sotto **URI di reindirizzamento autorizzati**, aggiungi l'indirizzo della pagina `google/` del sito (con la barra finale). Il flusso per "TV e dispositivi con input limitato" non si può usare: Google non vi ammette lo scope del Photos Picker.
8. Copia **ID client** e **Client secret**.
9. Inserisci i tre valori, a seconda di dove compili:
   - **GitHub Actions** (APK della CI): nel repository, **Settings › Secrets and variables › Actions**, crea i secret `PHOTOS_CLIENT_ID` e `PHOTOS_CLIENT_SECRET` e, nella scheda **Variables**, la variabile `PHOTOS_REDIRECT_URI` con l'indirizzo della pagina `google/`. Li usano sia la CI sia il workflow di release.
   - **Computer locale**: in `local.properties`, senza aggiungere il file a Git:

```properties
sdk.dir=/home/utente/Android/Sdk
PHOTOS_CLIENT_ID=123456789012-abc123.apps.googleusercontent.com
PHOTOS_CLIENT_SECRET=GOCSPX-...
PHOTOS_REDIRECT_URI=https://utente.github.io/fotofesta-tv/google/
```

10. Ricompila l’app: rilancia la CI (**Actions › Android CI › Run workflow**) o `./scripts/build.sh`. Se la TV ha già una versione installata con un'altra firma debug, disinstallala prima.

Senza credenziali, le build debug mostrano comunque la scheda Google Foto con un messaggio che spiega cosa manca, mentre le build di release la nascondono. Il workflow di release si ferma se mancano i secret o la variabile, salvo l'opzione "Build senza Google Foto".

Il client secret di un'applicazione web, secondo Google, andrebbe tenuto su un server: qui sta nell'app, come il secret del vecchio flusso per TV, perché il progetto non ha un server. Serve solo a scambiare e rinnovare i token insieme al codice che arriva dal telefono, protetto da PKCE; chi lo estraesse dall'app potrebbe al massimo presentare agli utenti una schermata di consenso con il nome di FotoFesta. Se Google lo rigenerasse, basta aggiornare il secret e ricompilare.

L’app chiede esclusivamente lo scope `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`, che consente di creare sessioni Picker e recuperare solo gli elementi selezionati.[2] Per una pubblicazione fuori dal gruppo di test, Google richiede la verifica OAuth dell’app Photos API.[2]

### Flusso utente

La TV apre sulla rete di casa una pagina (`shared/assets/guest/google.html`, come quella degli invitati) e ne mostra il QR. Il telefono la apre, tocca «Continua con Google» e va alla schermata di consenso di Google con il flusso "authorization code" e PKCE;[3] lo `state` porta, in base64url, l'indirizzo di ritorno sulla TV. Google rimanda il telefono alla pagina `site/google/` del sito, che legge lo `state`, controlla che l'indirizzo sia della rete di casa e ci rimanda il telefono con il codice (una navigazione da https a http, ammessa dai browser al contrario di una `fetch`). La TV scambia il codice con i token (client secret e `code_verifier`), crea una sessione Picker con UUID v4, mostra un secondo QR e offre lo stesso link anche sulla pagina del telefono, poi attende `mediaItemsSet=true`, ricalcolando la scadenza da `pollingConfig.timeoutIn` a ogni risposta. La pagina della TV vuole la chiave del QR, controlla l'intestazione `Host`, resta accesa dieci minuti al massimo e consegna un codice una volta sola. Le foto **non vengono scaricate**: l’app salva solo un piccolo file con l’identificativo della sessione Picker, la sua scadenza e identificativi e nomi delle foto scelte. Durante slideshow, giochi e salvaschermo ogni foto si carica da Google già ridotta alla dimensione dello schermo (`=w…-h…`, con `-c` quando deve riempire il riquadro) e resta solo nella cache in RAM. Gli indirizzi restituiti dalla Picker API valgono circa 60 minuti: l’app li rinnova richiedendo di nuovo l’elenco della sessione ogni 50 minuti, o subito dopo un errore 401/403. La sessione resta aperta finché Google la tiene valida (`expireTime`, mostrato all’utente a fine collegamento; Google non ne garantisce la durata): quando l’elenco risponde 404, o 400/403 confermati da `sessions.get`, l’app avvisa che le foto vanno scelte di nuovo. Se l’accesso viene revocato avvisa che serve un nuovo collegamento. La scadenza non si decide con l’orologio della TV, che all’accensione può essere sbagliato. La sessione viene eliminata con “Disconnetti e cancella”, quando una nuova selezione la sostituisce, quando si passa a un’altra sorgente o se il collegamento non arriva in fondo. All’avvio l’app elimina le foto scaricate dalle versioni fino alla 1.2.0.

Se l’utente revoca l’accesso dall’account Google, al collegamento successivo l’app cancella i token non più validi e mostra di nuovo il QR per il telefono, senza richiedere “Disconnetti e cancella”.

Il `client_secret` di un client installato/TV è un identificatore distribuito nell’app e non deve essere considerato una credenziale server realmente segreta. La sicurezza dell’utente dipende da consenso OAuth, scope minimo, refresh token cifrato e possibilità di revoca. Non riutilizzare queste credenziali in un backend riservato.

## 3. Foto degli invitati

Le foto degli invitati non richiedono configurazione né account: funzionano con la build così com’è, su Android TV e Apple TV.

**Come funziona.** “Foto degli invitati › Inizia a ricevere foto” avvia un server HTTP sulla rete di casa. La TV mostra un QR con l’indirizzo `http://<IP della TV>:8765/?k=<chiave>`; se la porta 8765 è occupata ne usa un’altra libera. La chiave è casuale (128 bit), resta la stessa per tutta la festa, così il QR stampato o fotografato continua a funzionare, e cambia alla festa successiva. Il telefono apre la pagina [`shared/assets/guest/index.html`](../shared/assets/guest/index.html), servita dalla TV con i valori dello schermo: la pagina riduce ogni foto alla risoluzione della TV con un margine del 10% per il movimento lento, rispettando l’adattamento scelto (foto intera o riempimento). La ricodifica in JPEG di qualità 0,88 senza metadati e la invia con `POST /photos`. Su Android TV la stessa pagina è inclusa come asset, su Apple TV come risorsa del bundle.

**Dove restano le foto.** In RAM finché c’è posto: su Android un quarto della memoria concessa all’app, tra 16 e 64 MB; su Apple TV 64 MB. Quando la memoria non basta, quando il sistema chiede di liberarne o quando l’app va in background, le più vecchie passano in una cartella temporanea: `noBackupFilesDir/guest_photos` su Android, `Caches/guest_photos` su Apple TV. La cartella non supera 100 MB (300 MB sulle TV che disegnano in 4K e su Apple TV) né il 10% dello spazio libero; se si riempie, le foto più vecchie vengono cancellate per far posto alle nuove. “Termina la festa e cancella le foto” cancella RAM e cartella. Se l’app viene chiusa durante la festa, al riavvio riprende le foto della cartella, a meno che l’ultima foto sia arrivata più di 12 ore prima: in quel caso la festa si chiude e la cartella viene svuotata.

**Approvazione.** Con “Approvazione delle foto” attiva, le foto nuove restano in attesa finché l’organizzatore non le approva con il telecomando (Mostra, Scarta, Approva tutte). Disattivandola, le foto in attesa vengono approvate.

**QR della Wi‑Fi.** Facoltativo: l’organizzatore scrive nome e password della rete e la TV mostra un secondo QR nel formato `WIFI:T:WPA;S:…;P:…;;`, riconosciuto dalle fotocamere di Android e iPhone. La password è cifrata con Android Keystore o salvata nel Portachiavi della Apple TV, e non lascia il dispositivo.

**Sicurezza.** Il server è acceso solo mentre l’app è in primo piano e la festa è in corso. Su Android ascolta solo sull’indirizzo IPv4 della rete di casa; su Apple TV ascolta sulle interfacce della TV, che dietro il router di casa sono raggiungibili solo dalla rete locale. Richiede la chiave sia per la pagina sia per l’invio e confronta l’intestazione `Host` con l’indirizzo del QR, contro il DNS rebinding. Accetta solo JPEG, PNG e WebP riconosciuti dai primi byte e decodificabili, con lati fino a 8.192 pixel e file fino a 12 MB. Rifiuta trasferimenti chunked e `Content-Length` ambigui. Limita le intestazioni a 16 KB e i tempi totali a 15 s per le intestazioni e 120 s per la foto, anche se i byte arrivano uno alla volta. Accetta al massimo 3 connessioni contemporanee e 60 invii al minuto per telefono e 24 MB di foto in arrivo contemporaneamente; oltre, risponde 503 o 429 con `Retry-After` e la pagina riprova da sola. Un invio ripetuto con lo stesso identificativo non salva la foto due volte. Le foto in attesa di approvazione sono al massimo 100 e vengono sacrificate prima di quelle approvate, così un invitato non può far sparire le foto già in mostra. La pagina è servita con `Content-Security-Policy: default-src 'none'` più gli script e gli stili inline necessari, `Cache-Control: no-store` e `Referrer-Policy: no-referrer`. Il traffico tra telefono e TV è HTTP sulla rete locale: chi controlla la rete può vederlo, come per qualsiasi dispositivo di casa senza HTTPS.

**Rete.** Telefono e TV devono essere sulla stessa rete. Le reti “ospiti” e l’isolamento dei client (AP isolation) di alcuni router bloccano il traffico tra dispositivi: in quel caso la pagina non si apre e gli invitati vanno collegati alla rete principale. Sui router con più reti (2,4 e 5 GHz) di solito il traffico passa tra le due. Su Apple TV la descrizione `NSLocalNetworkUsageDescription` è già in `Info.plist`.

## 4. Cartelle di rete e video

**Cartelle di rete (Android TV e Apple TV).** Non richiedono configurazione nell’app: serve un server multimediale DLNA/UPnP acceso sulla stessa rete della TV, ad esempio il server multimediale di un NAS (Synology Media Server, QNAP, WD), un router con un disco USB e la condivisione multimediale attiva (FRITZ!Box, molti router degli operatori), Windows con “Opzioni flusso multimediale”, Plex, Jellyfin, Emby o MiniDLNA. In “Cartelle” la TV li cerca con SSDP (multicast su `239.255.255.250:1900`, risposta diretta alla TV), legge la descrizione del dispositivo e sfoglia le cartelle con `ContentDirectory:Browse`, a pagine di 500 elementi. “Usa questa cartella” prende foto e video anche dalle sottocartelle, fino a 10.000 elementi e 2.000 cartelle, saltando album, artisti, generi e playlist musicali. La TV salva l’identificativo del server (UDN), l’indirizzo della sua descrizione e la cartella: se il server cambia indirizzo IP viene ritrovato in rete. Quando il server offre più versioni di una foto, la TV prende la più leggera che copre lo schermo (un originale da 24 megapixel pesa decine di MB e verrebbe comunque ridotto); senza dimensioni dichiarate usa l’originale.

Su **Apple TV** la domanda SSDP in multicast richiede l’entitlement `com.apple.developer.networking.multicast`, concesso da Apple su richiesta (vedi [apple/README.md](../apple/README.md)). Senza, nella schermata “Cartelle di rete” si scrive l’indirizzo IP del NAS o del PC: l’app gli manda la domanda SSDP direttamente (unicast, non richiede l’entitlement) e prova le descrizioni di MiniDLNA (`:8200/rootDesc.xml`), Synology Media Server (`:50001/desc/device.xml`), Plex (`:32469/DeviceDescription.xml`), Twonky (`:9000/TMSDeviceDescription.xml`) e FRITZ!Box (`:49000/MediaServerDevDesc.xml`). L’http in chiaro è ammesso da `NSAllowsLocalNetworking` e l’app lo usa solo verso indirizzi privati.

I server DLNA usano HTTP in chiaro. L’app Android continua a vietarlo nella configurazione di rete (`network_security_config.xml`): le richieste verso i server passano da `LanHttp`, un client minimo che si collega solo a indirizzi privati (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, link-local, fc00::/7), anche dopo un reindirizzamento, e rifiuta l’XML con DOCTYPE. Foto e video si leggono quando servono e non vengono copiati.

**Cartelle condivise SMB (solo Android TV).** Nel browser “Cartelle” la sezione “Cartelle condivise (PC, Mac, NAS)” mostra i computer che si annunciano con Bonjour (`_smb._tcp`: Mac con la condivisione file attiva, Synology e QNAP con Bonjour attivo, Linux con Samba e Avahi) e la riga “Aggiungi un computer o un NAS”. I PC Windows non si annunciano: si aggiungono con l’indirizzo IP (in Windows: Impostazioni › Rete e Internet › proprietà della rete) o con il nome `\\NOMEPC\Cartella`. Si accede con nome utente e password del computer (su Windows quelli dell’account, anche l’email dell’account Microsoft; “DOMINIO\utente” se serve) oppure come ospite lasciando vuoto il nome, se il NAS lo permette. La TV elenca le condivisioni con DCE/RPC (`srvsvc`, come Esplora file); se il NAS non lo consente si scrive il nome della condivisione. Serve SMB 2 o 3: sui NAS va disattivato l’obbligo di SMB 1. “Usa questa cartella” prende foto e video anche dalle sottocartelle (fino a 10.000 elementi e 2.000 cartelle), saltando file nascosti, cestini e miniature dei NAS (`@eaDir`, `#recycle`, `$RECYCLE.BIN`).

La password resta sulla TV, cifrata con AES-GCM e una chiave non esportabile del Keystore di Android, nel file escluso dal backup; si usa solo per leggere i file. Le connessioni hanno un limite di 8 s per collegarsi e 30 s per ogni risposta; se il computer chiude la connessione (standby del NAS, riavvio) la TV la riapre al primo file. Foto e video si leggono al momento: le foto per intero, i video a blocchi da 256 KB, per spostarsi nel file senza scaricarlo tutto.

**Album condiviso di iCloud (solo Android TV).** Sull’iPhone: app Foto › album condiviso › icona delle persone › “Sito web pubblico” attivo. Nel browser “Cartelle” › “Album condiviso iCloud” la TV mostra un QR: il telefono apre una pagina servita dalla TV (stessa sicurezza della pagina degli invitati: chiave casuale, controllo di `Host`, moduli fino a 4 KB, accesa solo mentre la schermata è aperta) dove incollare il link `https://www.icloud.com/sharedalbum/#…`. In alternativa si scrive con il telecomando il codice dopo “#”. La TV legge l’elenco con il servizio web della pagina pubblica (`p…-sharedstreams.icloud.com`, non documentato da Apple), mostra nome, autore e quanti elementi ha l’album e, con “Usa questo album”, lo usa come raccolta. Ogni foto si chiede nella versione che basta allo schermo; gli indirizzi dei file scadono e vengono rinnovati in gruppi da 25. L’album si rilegge ogni 5 minuti: le foto aggiunte compaiono da sole. Sulla TV restano solo il codice e il nome dell’album, esclusi dal backup.

**Foto di iCloud su Apple TV.** Nella home, “Foto di iCloud” mostra gli album della libreria Foto della Apple TV (tutte le foto, preferiti, album, album condivisi) dopo il permesso di tvOS. Servono Foto di iCloud o Album condivisi attivi in Impostazioni › Utenti e account › iCloud. Le foto arrivano da PhotoKit nella dimensione dello schermo; quelle aggiunte all’album entrano da sole nello slideshow.

**Video.** Su Android TV i video di cartelle USB, cartelle di rete e condivise, album iCloud e Google Foto entrano nella raccolta e nello slideshow, dove si guardano fino alla fine; li riproduce Media3 (ExoPlayer) con i contenitori MP4, MOV, 3GP, MKV, WebM, AVI, TS/M2TS, MPEG, FLV e OGV. I codec (H.264, HEVC, VP9, AV1, AC‑3…) dipendono dalla TV: un video che non si può decodificare mostra un avviso e viene saltato. Da Android 13 i video delle cartelle USB lette dal percorso richiedono `READ_MEDIA_VIDEO`, chiesto insieme a `READ_MEDIA_IMAGES` all’apertura del browser. Su Apple TV si vedono i video di Google Foto, della libreria Foto e delle cartelle di rete in MP4 o MOV (H.264, HEVC). In entrambe le app “Video” li esclude e “Audio dei video” li rende muti; su Android, mentre un video suona, la musica di sottofondo si ferma. Giochi, sfondo della home, caselle del mosaico e salvaschermo usano solo le foto.

**Gioco con i telefoni.** Durante la festa, in “Foto misteriosa” e “Chi l’ha scattata?”, “Con i telefoni” pubblica la domanda sulla pagina della festa: la pagina interroga `GET /game` ogni 1,5 secondi e risponde con `POST /game/answer` (modulo `round`, `choice`, `player`, `name`, al massimo 1 KB), con la stessa chiave del QR. Il server risponde 409 se la domanda è chiusa e 400 se la risposta non è valida. Ogni telefono ha un identificativo casuale salvato nel browser; vale la sua prima risposta. Risposte, nomi e punti restano in memoria e si azzerano con una nuova partita.

**Funzioni della TV Android.** Nelle impostazioni, sezione “TV”:

- *Modalità leggera*: niente sfondo sfocato, zoom lenti, mosaico, pila, zapping e coriandoli. Si accende da sola sulle TV con `isLowRamDevice` o con al massimo 128 MB di memoria per app.
- *Proiettore*: contrasto ×1,18 e saturazione ×1,2 (da Android 12, con `RenderEffect`) e bande nere al posto dello sfondo sfocato.
- *Avvio con la TV*: `BootReceiver` apre l’app con `EXTRA_AUTO_START` e parte lo slideshow; Android 10+ blocca l’apertura sulle TV che non la consentono.
- *Canale nella schermata Home*: canale `PreviewChannel` con quattro scorciatoie (`fotofesta://open/slideshow`, `party`, `games`, `shows`) e le immagini demo.
- *TV come schermo DLNA*: durante la festa un MediaRenderer UPnP (SSDP su 239.255.255.250:1900, AVTransport) annuncia la TV; `SetAVTransportURI` scarica l’immagine (max 20 MB, solo indirizzi privati) e la aggiunge alla festa. Video e musica vengono rifiutati.
- *Copia sulla chiavetta*: cartella scelta con il selettore di documenti; ogni foto della festa va in `FotoFesta/<aaaa-mm-gg>/`. Non con “Festa senza tracce”.

Nella schermata della festa “Rete Wi‑Fi della TV” crea un hotspot locale (`startLocalOnlyHotspot`, chiede `NEARBY_WIFI_DEVICES` o la posizione fino ad Android 12): il server della festa passa sul suo indirizzo e il QR della Wi‑Fi mostra la rete della TV. Si spegne a fine festa.

Nei giochi, “Pulsanti quiz” fa di ogni gamepad o tastiera una squadra (fino a 4, per `deviceId`): chi preme per primo dopo “Via!” risponde. “Telecamera e applausometro” legge `http://<telefono>:8080/video` (MJPEG) e `/audio.wav` (PCM 16 bit) di un’app “IP Webcam”; “Scatta” manda il fotogramma alla festa. Durante i giochi l’app chiede la modalità gioco della TV (`setPreferMinimalPostProcessing`, Android 11+).

Gli aggiornamenti dal Play Store (Play In-App Updates, flessibili) si scaricano in background e si installano solo con la home aperta e la festa non in corso.

## 5. Installare su Google TV

Per una prova locale:

```bash
adb connect INDIRIZZO_IP_TV:5555
adb install -r dist/FotoFesta-TV-debug.apk
```

Per Google Play caricare l’AAB generato dopo aver configurato una firma di release. Non distribuire l’APK debug: usa un certificato condiviso e non può essere aggiornato da una release firmata con un’altra chiave.

## 6. Firma release

Creare una chiave una sola volta e conservarla in un password manager/backup offline:

```bash
keytool -genkeypair \
  -keystore fotofesta-upload.jks \
  -alias fotofesta-upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Per firmare automaticamente l’AAB release aggiungere a `local.properties` (o come variabili d’ambiente in CI):

```properties
RELEASE_STORE_FILE=/percorso/sicuro/fotofesta-upload.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=fotofesta-upload
RELEASE_KEY_PASSWORD=...
```

Senza queste chiavi la build release resta non firmata e `scripts/build.sh` produce `FotoFesta-TV-release-unsigned.aab`; con le chiavi produce `FotoFesta-TV-release.aab`. `scripts/setup_android_sdk.sh` aggiorna solo `sdk.dir` e non tocca le altre chiavi.

Non aggiungere `.jks`, password o `local.properties` al repository. La soluzione preferita per il Play Store è **Play App Signing**, usando la chiave locale come upload key.

## 7. Checklist prima della pubblicazione

| Area | Azione |
| --- | --- |
| Google Foto | Verifica OAuth completata e account di test fornito ai reviewer |
| Foto degli invitati | Prova con due telefoni (Android e iPhone) sulla stessa Wi‑Fi della TV, approvazione, QR della Wi‑Fi, fine festa |
| Privacy | Informativa (`site/privacy/`) su URL pubblico e Data Safety coerente ([DATA_SAFETY.md](DATA_SAFETY.md)) |
| Sicurezza | Nessun segreto server, token o keystore nel repository |
| Store | AAB firmato, icona 512×512, grafica 1024×500, banner TV 1280×720 e screenshot reali |
| TV | Test D-pad, Back, standby/ripresa, rete assente, USB rimosso |
| Accessibilità | Test TalkBack, ordine focus, contrasto e riduzione movimento |
| Prestazioni | Prova con almeno 1.000 foto USB e spazio cache ridotto |
| Rete di casa | Cartella DLNA, cartella condivisa SMB (password e ospite, NAS in standby), album iCloud con link pubblico; su Apple TV libreria Foto e server aggiunto con l’indirizzo |
| Supporto | Email di assistenza e pagina di supporto (`site/index.html`) pubblicate |

## Riferimenti

[1]: https://developers.google.com/photos/support/updates "Updates to the Google Photos APIs"
[2]: https://developers.google.com/photos/overview/authorization "Google Photos API authorization scopes"
[3]: https://developers.google.com/identity/protocols/oauth2/web-server "Using OAuth 2.0 for Web Server Applications"
