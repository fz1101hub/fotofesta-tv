# Pubblicare FotoFesta TV su Google Play e App Store

Guida operativa, nell'ordine in cui conviene procedere. Le due app condividono il client OAuth Google, l'informativa privacy e la logica, ma si pubblicano separatamente:

- **Android TV / Google TV**: cartella `app/`, store Google Play.
- **Apple TV**: cartella `apple/`, store App Store.

## 0. Prima di tutto (vale per entrambi gli store)

### Informativa privacy e pagina di supporto

Entrambi gli store chiedono l'URL di un'informativa privacy. App Store chiede anche una pagina di supporto con un contatto, e Google la usa come home page nella verifica OAuth. Le due pagine sono pronte nella cartella `site/`:
- `site/index.html`: pagina di supporto, con descrizione, domande frequenti e contatto;
- `site/privacy/index.html`: informativa privacy in italiano e inglese.

1. Completa i campi evidenziati in giallo: titolare, indirizzo, email e data.
2. Pubblica **solo** la cartella `site/`, scegliendo una di queste strade:
   - **GitHub Pages**: richiede un repository pubblico oppure un piano GitHub a pagamento. In **Settings › Pages** scegli **Source: GitHub Actions**. Il workflow `.github/workflows/pages.yml` pubblica `site/` a ogni modifica su `main` e si ferma se trova campi ancora da completare. L'indirizzo sarà `https://<utente>.github.io/fotofesta-tv/`.
   - **Un altro hosting statico gratuito**, come Cloudflare Pages o Netlify: carica il contenuto di `site/` così com'è.
   - **Un tuo dominio**: copia i due file nel sito.
3. Annota i due URL, ad esempio `https://<sito>/` per il supporto e `https://<sito>/privacy/` per l'informativa.
4. Scrivili al posto di `EXAMPLE`:
   - in `apple/fastlane/metadata/*/support_url.txt` e `privacy_url.txt`;
   - in `PrivacyPolicyURL` di `apple/project.yml`: l'app Apple TV mostra l'informativa come QR nelle impostazioni, perché tvOS non apre i link.
5. In `apple/fastlane/metadata/copyright.txt` sostituisci `[Titolare]` con il tuo nome o quello della tua attività.

### Client OAuth Google (facoltativo, serve solo per Google Foto)

Le due app funzionano anche senza Google Foto, con le foto demo, le foto degli invitati e, su Android, le cartelle USB.

1. In [Google Cloud Console](https://console.cloud.google.com/) abilita **Google Photos Picker API**.
2. Configura la **OAuth consent screen** (Google Auth Platform › Branding):
   - nome dell'app ed email di supporto;
   - **home page**: l'URL della pagina di supporto;
   - URL dell'informativa privacy;
   - utenti di test.

   Aggiungi il dominio del sito tra i **domini autorizzati** e verificane la proprietà in [Google Search Console](https://search.google.com/search-console). Per un sito `*.github.io` serve la proprietà con prefisso URL `https://<utente>.github.io/fotofesta-tv/`.
3. Crea un client OAuth di tipo **TVs and Limited Input devices**. Lo stesso client va bene per Android TV e Apple TV.
4. Prima di inviare le app in revisione, porta lo stato di pubblicazione dell'app OAuth su **In produzione** e invia la verifica a Google, indicando lo scope `photospicker.mediaitems.readonly` e il video dimostrativo richiesto. In modalità test possono collegarsi solo gli utenti di test (massimo 100), e i loro token scadono dopo 7 giorni: i revisori degli store non riuscirebbero ad accedere con un proprio account.
5. Prepara un account Google di test autorizzato, da fornire ai revisori di entrambi gli store.

## 1. Google Play (Android TV)

### Una volta sola

1. **Account Play Console**: si registra su [play.google.com/console](https://play.google.com/console) con un costo una tantum.
   - **Account personali creati dopo novembre 2023**: Google richiede un test chiuso con almeno 12 tester per 14 giorni prima di poter pubblicare in produzione. Pianificalo.
2. **Upload key**: la generi con
   ```bash
   keytool -genkeypair -keystore fotofesta-upload.jks -alias fotofesta-upload -keyalg RSA -keysize 4096 -validity 10000
   ```
   Conservala fuori dal repository, con il backup delle password.
3. **Firma locale**: aggiungi a `local.properties` le chiavi `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` e, se usi Google Foto, `PHOTOS_CLIENT_ID` e `PHOTOS_CLIENT_SECRET`.
4. **Firma in CI**: crea in **Settings › Secrets and variables › Actions** i secret con gli stessi nomi, più `RELEASE_KEYSTORE_BASE64`. Quest'ultimo si ottiene con `openssl base64 -A -in fotofesta-upload.jks`, che funziona sia su macOS sia su Linux.

### Generare l'AAB firmato

Hai due modi:
- **In locale**: `./scripts/build.sh`. Produce `dist/FotoFesta-TV-release.aab` ed esegue test e lint.
- **Con GitHub Actions**: avvia **Actions › Android release › Run workflow** e scarica l'artefatto `FotoFesta-TV-release`, che contiene AAB e `mapping.txt`. Anche un tag `v*`, ad esempio `v1.3.0`, avvia il workflow, ma produce solo l'artefatto e non carica nulla su Play.

La versione 1.4.0 compila e supera test e Lint nella CI (`.github/workflows/android.yml`); la build di release firmata va comunque provata su una TV prima di caricarla.

### Creare l'app in Play Console

1. **Crea app**: nome "FotoFesta TV", lingua predefinita, tipo "App", gratuita. Indica l'**email di contatto** pubblica, obbligatoria, e se vuoi il sito della pagina di supporto (**Presenza nello store › Dettagli di contatto**).
2. **Configura app**: compila le sezioni nell'ordine proposto.
   - **Accesso all'app**: tutte le funzioni sono disponibili senza login tramite "Prova subito" e "Foto degli invitati" (basta un telefono sulla stessa rete della TV). Per Google Foto fornisci l'account di test e la procedura dei due QR.
   - **Annunci**: no.
   - **ID pubblicità**: no. Il manifest rimuove il permesso `AD_ID`.
   - **Autorizzazioni per foto e video** (*Contenuti dell'app › Autorizzazioni per foto e video*): l'app chiede `READ_MEDIA_IMAGES` per leggere le foto dal disco USB. Usa la motivazione di [DATA_SAFETY.md](DATA_SAFETY.md#autorizzazioni-per-foto-e-video). `READ_MEDIA_AUDIO`, per il brano di sottofondo, non richiede dichiarazioni.
   - **Classificazione dei contenuti**: questionario. Nessuna violenza, nessuna interazione tra utenti attraverso l'app. Le foto degli invitati restano sulla TV dell'organizzatore, che può approvarle prima che compaiano e cancellarle in ogni momento: non vengono pubblicate né condivise ([DATA_SAFETY.md](DATA_SAFETY.md#altre-sezioni-di-play-console)).
   - **Pubblico di destinazione**: 13+ oppure 18+. Scegliendo fasce di età più basse si applicano le norme Families.
   - **Sicurezza dei dati**: usa le risposte di [DATA_SAFETY.md](DATA_SAFETY.md). Dalla 1.3.0 l'app non include SDK che raccolgono dati, quindi la risposta è "nessun dato raccolto".
   - **Informativa privacy**: l'URL del punto 0.
3. **Scheda dello store principale**:
   - Testi da `fastlane/metadata/android/<lingua>/`.
   - Icona, grafica in evidenza e banner TV da `.../images/`.
   - Le schede sono pronte in italiano, inglese, francese, spagnolo e tedesco: `fastlane android metadata` le carica tutte.
   - Screenshot: 8 schermate TV a 1920×1080 per ogni lingua sono già in `.../images/tvScreenshots/`, catturate dall'emulatore Android TV della CI (vedi [fastlane/metadata/android/README.md](../fastlane/metadata/android/README.md)); `fastlane android metadata` le carica con i testi. Se cambi l'interfaccia, rifalle con `[screenshots]` nel messaggio del commit.
4. **Form factor Android TV**: in **Impostazioni avanzate › Form factor** aggiungi **Android TV** e accetta i requisiti. La revisione TV verifica:
   - banner e icona Leanback;
   - navigazione completa con il telecomando;
   - nessun touchscreen richiesto.
5. **Prima release**:
   1. **Test › Test interno › Crea release**.
   2. Accetta **Play App Signing**.
   3. Carica l'AAB.
   4. Aggiungi i tester.
   5. Leggi il **Rapporto pre-lancio**.
6. **Test chiuso**, se richiesto dal tuo tipo di account, poi **Produzione**: crea la release, scegli i paesi e invia per la revisione.

### Rilasci successivi automatici (facoltativo)

Questi passaggi funzionano solo dopo il primo caricamento manuale.

1. In Google Cloud crea un account di servizio, abilita la **Google Play Android Developer API** e scarica la chiave JSON.
2. In Play Console invita l'account di servizio da **Utenti e autorizzazioni**, con i permessi di rilascio.
3. Salva la chiave JSON nel secret `PLAY_SERVICE_ACCOUNT_JSON`.
4. Per ogni versione:
   1. aumenta `versionCode` e `versionName` in `app/build.gradle.kts`;
   2. aggiungi le note in `fastlane/metadata/android/<lingua>/changelogs/<versionCode>.txt`;
   3. avvia **Android release** con **Carica su Google Play** attivo.

   L'AAB arriva come bozza nel test interno; da lì si promuove con `bundle exec fastlane android promote_production` o dalla console.

## 2. App Store (Apple TV)

### Una volta sola

> **Stato:** il workflow `.github/workflows/apple.yml` compila l'app per tvOS con Xcode 26.3 ed esegue i 114 test del nucleo su un runner macOS, senza errori. L'archivio firmato e la prova su una Apple TV reale restano da fare; vedi [apple/README.md](../apple/README.md#stato-di-verifica).

1. **Strumenti**:
   - iscrizione all'[Apple Developer Program](https://developer.apple.com/programs/) (abbonamento annuale);
   - un Mac con **Xcode 26 o successivo**: da aprile 2026 App Store Connect accetta solo build fatte con l'SDK tvOS 26;
   - in Xcode, **Settings › Accounts**: accedi con l'Apple ID del Developer Program. Serve alla firma automatica usata da Xcode e da fastlane (`-allowProvisioningUpdates`);
   - `brew install xcodegen` e, per fastlane, Ruby con Bundler: dalla radice del repository `bundle install` installa le versioni fissate in `Gemfile.lock`.
2. **Progetto**:
   ```bash
   cd apple
   cp Config/Secrets.example.xcconfig Config/Secrets.xcconfig   # inserisci Team ID e, se usi Google Foto, il client
   xcodegen generate
   (cd FotoFestaCore && swift test)                               # test del nucleo logico
   open FotoFestaTV.xcodeproj
   ```
   Prova l'app nel simulatore Apple TV e su una Apple TV reale.
3. **App Store Connect › App › +**: piattaforma **tvOS**, nome "FotoFesta TV", lingua principale, bundle ID `it.fotofesta.tv` (registrato in automatico dalla firma di Xcode) e uno SKU a piacere.
4. **Privacy dell'app**: scegli **Nessun dato raccolto** (motivazione in [DATA_SAFETY.md](DATA_SAFETY.md)) e inserisci l'URL dell'informativa.
5. **Classificazione per età**: questionario, risultato atteso 4+.
6. **Entitlement multicast (facoltativo, per le cartelle di rete)**: perché la Apple TV trovi da sola i server DLNA serve `com.apple.developer.networking.multicast`. Si chiede ad Apple con il modulo [Multicast Networking Entitlement Request](https://developer.apple.com/contact/request/networking-multicast) (motivo: scoperta dei server multimediali DLNA/UPnP della rete di casa con SSDP). Dopo l'approvazione, in **Certificates, Identifiers & Profiles** attiva la capability per l'identificativo `it.fotofesta.tv`, imposta `FOTOFESTA_ENTITLEMENTS = FotoFestaTV/Multicast.entitlements` in `Config/App.xcconfig` (o in `Secrets.xcconfig`) e rigenera il progetto. Senza l'entitlement l'app funziona lo stesso: i server si aggiungono con l'indirizzo IP. Non attivarlo prima dell'approvazione: la firma fallirebbe.
7. **Chiave API** per fastlane: crea la chiave in **Utenti e accessi › Integrazioni › Chiavi API App Store Connect**. Esporta `ASC_KEY_ID`, `ASC_ISSUER_ID` e `ASC_KEY_PATH`, dove `ASC_KEY_PATH` è il file `.p8`, da tenere fuori dal repository.

### Pubblicare una versione

1. Aggiorna `MARKETING_VERSION` in `apple/project.yml`. Il numero di build lo assegna fastlane (ultimo su TestFlight + 1). Se invece carichi da Xcode, porta `CURRENT_PROJECT_VERSION` oltre l'ultimo numero caricato.
2. Carica la build, in uno di due modi:
   - `cd apple && bundle exec fastlane tvos beta`, che esegue i test, compila, firma, assegna un numero di build nuovo e carica su TestFlight;
   - in Xcode: **Product › Archive › Distribute App › App Store Connect**.
3. Prova la build con **TestFlight** su una Apple TV reale.
4. Prepara le **Informazioni per la revisione** prima di caricare la scheda:
   - in `apple/fastlane/metadata/review_information/` scrivi nome, cognome, telefono ed email di contatto al posto di `[da completare]`;
   - l'account Google di test non va scritto nel repository. Passalo a fastlane con le variabili d'ambiente:
     ```bash
     export REVIEW_DEMO_USER="account-di-test@gmail.com"
     export REVIEW_DEMO_PASSWORD="..."
     ```
     È consigliato: "Prova subito" funziona senza login, ma l'account permette ai revisori di provare Google Foto. Se le variabili sono impostate, fastlane aggiunge alle note per i revisori la frase che rimanda all'account; altrimenti le note non lo citano.
   - con `PHOTOS_CLIENT_ID` vuoto in `Config/Secrets.xcconfig` la build non mostra Google Foto e l'account non serve. In quel caso togli Google Foto da `description.txt`, `promotional_text.txt` e `apple_tv_privacy_policy.txt` di ogni lingua e dalle note per i revisori, poi esegui `release` con `ALLOW_NO_GOOGLE=1`: senza, fastlane si ferma.
   - in `apple_tv_privacy_policy.txt` di ogni lingua (testo dell'informativa mostrato su Apple TV) completa titolare e contatto al posto di `[da completare]`.
5. Carica la scheda:
   - testi: `bundle exec fastlane tvos metadata`, che prende i dati da `apple/fastlane/metadata/` (compresa l'informativa breve mostrata su Apple TV, `apple_tv_privacy_policy.txt`) e si ferma se trova ancora segnaposto (`[da completare]`, `EXAMPLE`, `[Titolare]`);
   - screenshot: 1920×1080 o 3840×2160, catturabili dal simulatore con **File › Save Screen**. Bastano quelli della lingua principale: le altre localizzazioni (italiano, inglese, francese, spagnolo, tedesco) li usano se non ne hanno di propri.
6. Invia per la revisione dalla pagina della versione.

Note per la revisione Apple:
- **Sign in with Apple** non è richiesto: Google si usa solo per accedere ai contenuti dell'utente su Google Foto (eccezione della linea guida 4.8).
- **Crittografia**: l'app usa solo la crittografia di sistema (HTTPS verso Google). `ITSAppUsesNonExemptEncryption` è già impostato a `false`.
- **Funzione dimostrativa**: senza account, "Prova subito" mostra tutte le funzioni. Le foto degli invitati si provano con un iPhone sulla stessa rete della Apple TV: nelle note per i revisori spiega di inquadrare il QR in "Foto degli invitati".
- **Rete locale**: la Apple TV apre un server sulla rete di casa solo durante la festa e legge le cartelle dei server multimediali; `NSLocalNetworkUsageDescription` ne spiega il motivo. L'http in chiaro è ammesso solo verso la rete locale (`NSAllowsLocalNetworking`).
- **Libreria Foto**: “Foto di iCloud” chiede il permesso di tvOS con `NSPhotoLibraryUsageDescription`; le foto si mostrano e basta. Nelle note per i revisori è spiegato come provarla e come aggiungere un server DLNA con l'indirizzo.

## 3. Verifiche finali prima di inviare

| Verifica | Android TV | Apple TV |
| --- | --- | --- |
| Build di release firmata installata su un dispositivo reale | ☐ | ☐ |
| Navigazione completa con il telecomando, tasto Indietro/Menu ovunque | ☐ | ☐ |
| Prova subito, slideshow, impostazioni, giochi | ☐ | ☐ |
| Google Foto: collegamento, annullamento, selezione, "Disconnetti e cancella" | ☐ | ☐ |
| USB con molte foto, rimozione del disco durante lo slideshow | ☐ | — |
| Browser delle cartelle: permesso concesso e negato, disco USB, scelta del brano, TV con e senza selettore di Android | ☐ | — |
| Musica, salvaschermo, pausa automatica | ☐ | pausa automatica |
| Lingua del dispositivo in italiano, inglese, francese, spagnolo e tedesco (testi tagliati sui pulsanti) | ☐ | ☐ |
| Rete assente all'avvio e durante il collegamento | ☐ | ☐ |
| Foto degli invitati: invio da Android e iPhone, approvazione, QR della Wi‑Fi, uscita e ritorno nell'app, fine festa | ☐ | ☐ |
| Cartelle di rete DLNA: NAS reale, server spento, cambio di indirizzo IP | ☐ | ☐ (aggiunto con l'indirizzo) |
| Cartelle condivise SMB: Windows con password, NAS come ospite, NAS in standby, password sbagliata | ☐ | — |
| Album condiviso iCloud con link pubblico: QR dal telefono, codice scritto, foto aggiunte, link reso privato | ☐ | — |
| Foto di iCloud: permesso concesso e negato, album condiviso, foto aggiunte da un iPhone | — | ☐ |
| Informativa privacy pubblicata e URL inseriti negli store | ☐ | ☐ |
| Catena di build: `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` (somma dal sito di Gradle) e verifica delle dipendenze (`gradle/verification-metadata.xml`); `LICENSE` del progetto scelta | ☐ | — |
