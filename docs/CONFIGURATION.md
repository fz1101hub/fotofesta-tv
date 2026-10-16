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
6. In **Google Auth Platform › Client** premi **Crea client**, scegli il tipo **TV e dispositivi con input limitato**, dai un nome e crea.
7. Copia **ID client** e **Client secret**.
8. Inserisci i due valori, a seconda di dove compili:
   - **GitHub Actions** (APK della CI): nel repository, **Settings › Secrets and variables › Actions › New repository secret**, crea `PHOTOS_CLIENT_ID` e `PHOTOS_CLIENT_SECRET`. Li usano sia la CI sia il workflow di release.
   - **Computer locale**: in `local.properties`, senza aggiungere il file a Git:

```properties
sdk.dir=/home/utente/Android/Sdk
PHOTOS_CLIENT_ID=123456789012-abc123.apps.googleusercontent.com
PHOTOS_CLIENT_SECRET=GOCSPX-...
```

9. Ricompila l’app: rilancia la CI (**Actions › Android CI › Run workflow**) o `./scripts/build.sh`. Se la TV ha già una versione installata con un'altra firma debug, disinstallala prima.

Senza credenziali, le build debug mostrano comunque la scheda Google Foto con un messaggio che spiega cosa manca, mentre le build di release la nascondono. Il workflow di release si ferma se mancano i secret, salvo l'opzione "Build senza Google Foto".

L’app chiede esclusivamente lo scope `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`, che consente di creare sessioni Picker e recuperare solo gli elementi selezionati.[2] Per una pubblicazione fuori dal gruppo di test, Google richiede la verifica OAuth dell’app Photos API.[2]

### Flusso utente

La TV richiede un `device_code`, mostra QR, URL e `user_code`, quindi rispetta l’intervallo di polling restituito da Google.[3] Dopo l’autorizzazione, crea una sessione Picker con UUID v4, mostra un secondo QR e attende `mediaItemsSet=true`, ricalcolando la scadenza da `pollingConfig.timeoutIn` a ogni risposta. Le foto **non vengono scaricate**: l’app salva solo un piccolo file con l’identificativo della sessione Picker, la sua scadenza e identificativi e nomi delle foto scelte. Durante slideshow, giochi e salvaschermo ogni foto si carica da Google già ridotta alla dimensione dello schermo (`=w…-h…`, con `-c` quando deve riempire il riquadro) e resta solo nella cache in RAM. Gli indirizzi restituiti dalla Picker API valgono circa 60 minuti: l’app li rinnova richiedendo di nuovo l’elenco della sessione ogni 50 minuti, o subito dopo un errore 401/403. La sessione resta aperta finché Google la tiene valida (`expireTime`, mostrato all’utente a fine collegamento; Google non ne garantisce la durata): quando l’elenco risponde 404, o 400/403 confermati da `sessions.get`, l’app avvisa che le foto vanno scelte di nuovo. Se l’accesso viene revocato avvisa che serve un nuovo collegamento. La scadenza non si decide con l’orologio della TV, che all’accensione può essere sbagliato. La sessione viene eliminata con “Disconnetti e cancella”, quando una nuova selezione la sostituisce, quando si passa a un’altra sorgente o se il collegamento non arriva in fondo. All’avvio l’app elimina le foto scaricate dalle versioni fino alla 1.2.0.

Se l’utente revoca l’accesso dall’account Google, al collegamento successivo l’app cancella i token non più validi e mostra di nuovo il codice di autorizzazione, senza richiedere “Disconnetti e cancella”.

Il `client_secret` di un client installato/TV è un identificatore distribuito nell’app e non deve essere considerato una credenziale server realmente segreta. La sicurezza dell’utente dipende da consenso OAuth, scope minimo, refresh token cifrato e possibilità di revoca. Non riutilizzare queste credenziali in un backend riservato.

## 3. Foto degli invitati

Le foto degli invitati non richiedono configurazione né account: funzionano con la build così com’è, su Android TV e Apple TV.

**Come funziona.** “Foto degli invitati › Inizia a ricevere foto” avvia un server HTTP sulla rete di casa. La TV mostra un QR con l’indirizzo `http://<IP della TV>:8765/?k=<chiave>`; se la porta 8765 è occupata ne usa un’altra libera. La chiave è casuale (128 bit), resta la stessa per tutta la festa, così il QR stampato o fotografato continua a funzionare, e cambia alla festa successiva. Il telefono apre la pagina [`shared/assets/guest/index.html`](../shared/assets/guest/index.html), servita dalla TV con i valori dello schermo: la pagina riduce ogni foto alla risoluzione della TV con un margine del 10% per il movimento lento, rispettando l’adattamento scelto (foto intera o riempimento). La ricodifica in JPEG di qualità 0,88 senza metadati e la invia con `POST /photos`. Su Android TV la stessa pagina è inclusa come asset, su Apple TV come risorsa del bundle.

**Dove restano le foto.** In RAM finché c’è posto: su Android un quarto della memoria concessa all’app, tra 16 e 64 MB; su Apple TV 64 MB. Quando la memoria non basta, quando il sistema chiede di liberarne o quando l’app va in background, le più vecchie passano in una cartella temporanea: `noBackupFilesDir/guest_photos` su Android, `Caches/guest_photos` su Apple TV. La cartella non supera 100 MB (300 MB sulle TV che disegnano in 4K e su Apple TV) né il 10% dello spazio libero; se si riempie, le foto più vecchie vengono cancellate per far posto alle nuove. “Termina la festa e cancella le foto” cancella RAM e cartella. Se l’app viene chiusa durante la festa, al riavvio riprende le foto della cartella, a meno che l’ultima foto sia arrivata più di 12 ore prima: in quel caso la festa si chiude e la cartella viene svuotata.

**Approvazione.** Con “Approvazione delle foto” attiva, le foto nuove restano in attesa finché l’organizzatore non le approva con il telecomando (Mostra, Scarta, Approva tutte). Disattivandola, le foto in attesa vengono approvate.

**QR della Wi‑Fi.** Facoltativo: l’organizzatore scrive nome e password della rete e la TV mostra un secondo QR nel formato `WIFI:T:WPA;S:…;P:…;;`, riconosciuto dalle fotocamere di Android e iPhone. La password è cifrata con Android Keystore o salvata nel Portachiavi della Apple TV, e non lascia il dispositivo.

**Sicurezza.** Il server è acceso solo mentre l’app è in primo piano e la festa è in corso. Su Android ascolta solo sull’indirizzo IPv4 della rete di casa; su Apple TV accetta solo indirizzi privati, link-local o loopback. Richiede la chiave sia per la pagina sia per l’invio e confronta l’intestazione `Host` con l’indirizzo del QR, contro il DNS rebinding. Accetta solo JPEG, PNG e WebP riconosciuti dai primi byte e decodificabili, con lati fino a 10.000 pixel e file fino a 12 MB. Rifiuta trasferimenti chunked e `Content-Length` ambigui e limita intestazioni (16 KB), tempi di lettura (20 s) e connessioni contemporanee. La pagina è servita con `Content-Security-Policy: default-src 'none'` più gli script e gli stili inline necessari, `Cache-Control: no-store` e `Referrer-Policy: no-referrer`. Il traffico tra telefono e TV è HTTP sulla rete locale: chi controlla la rete può vederlo, come per qualsiasi dispositivo di casa senza HTTPS.

**Rete.** Telefono e TV devono essere sulla stessa rete. Le reti “ospiti” e l’isolamento dei client (AP isolation) di alcuni router bloccano il traffico tra dispositivi: in quel caso la pagina non si apre e gli invitati vanno collegati alla rete principale. Sui router con più reti (2,4 e 5 GHz) di solito il traffico passa tra le due. Su Apple TV la descrizione `NSLocalNetworkUsageDescription` è già in `Info.plist`.

## 4. Installare su Google TV

Per una prova locale:

```bash
adb connect INDIRIZZO_IP_TV:5555
adb install -r dist/FotoFesta-TV-debug.apk
```

Per Google Play caricare l’AAB generato dopo aver configurato una firma di release. Non distribuire l’APK debug: usa un certificato condiviso e non può essere aggiornato da una release firmata con un’altra chiave.

## 5. Firma release

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

## 6. Checklist prima della pubblicazione

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
| Supporto | Email di assistenza e pagina di supporto (`site/index.html`) pubblicate |

## Riferimenti

[1]: https://developers.google.com/photos/support/updates "Updates to the Google Photos APIs"
[2]: https://developers.google.com/photos/overview/authorization "Google Photos API authorization scopes"
[3]: https://developers.google.com/identity/protocols/oauth2/limited-input-device "OAuth 2.0 for TV and Limited-Input Device Applications"
