# Configurazione di FotoFesta TV

Questa guida porta il progetto dalla build dimostrativa a una versione collegata al proprio account Google Photos API e registrata per Cast Connect.

## 1. Prerequisiti locali

Il progetto richiede JDK 17 e Android SDK. Lo script installa Platform 35/36 e i Build Tools necessari nella home dell’utente.

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

1. Aprire [Google Cloud Console](https://console.cloud.google.com/).
2. Creare o selezionare un progetto.
3. In **APIs & Services > Library**, abilitare **Google Photos Picker API**.
4. Configurare **OAuth consent screen** con nome, email di supporto, privacy policy e utenti di test.
5. In **Credentials**, creare un client OAuth del tipo **TVs and Limited Input devices**.
6. Copiare `Client ID` e `Client secret`.
7. Inserire i valori in `local.properties`, senza aggiungere il file a Git:

```properties
sdk.dir=/home/utente/Android/Sdk
PHOTOS_CLIENT_ID=000000000000-example.apps.googleusercontent.com
PHOTOS_CLIENT_SECRET=example-secret
```

8. Ricompilare l’app.

L’app chiede esclusivamente lo scope `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`, che consente di creare sessioni Picker e recuperare solo gli elementi selezionati.[2] Per una pubblicazione fuori dal gruppo di test, Google richiede la verifica OAuth dell’app Photos API.[2]

### Flusso utente

La TV richiede un `device_code`, mostra QR, URL e `user_code`, quindi rispetta l’intervallo di polling restituito da Google.[3] Dopo l’autorizzazione, crea una sessione Picker con UUID v4, mostra un secondo QR e attende `mediaItemsSet=true`. Le foto vengono recuperate a 4K nella cache privata. La sessione viene eliminata al termine.

Il `client_secret` di un client installato/TV è un identificatore distribuito nell’app e non deve essere considerato una credenziale server realmente segreta. La sicurezza dell’utente dipende da consenso OAuth, scope minimo, refresh token cifrato e possibilità di revoca. Non riutilizzare queste credenziali in un backend riservato.

## 3. Configurare Google Cast Connect

Cast usa un’architettura Sender/Receiver. Per una soluzione completa, Google raccomanda sender Android/iOS/Web e ricevitore Android TV, con un Web Receiver come fallback per dispositivi Chromecast senza l’app nativa.[4]

1. Accedere alla [Google Cast SDK Developer Console](https://cast.google.com/publish).
2. Registrare l’account sviluppatore. La documentazione Google indica una quota di registrazione di 5 USD.[5]
3. Creare un’applicazione ricevitore e annotare il **Receiver Application ID**.
4. Nei dettagli sender/receiver associare il package Android TV `it.fotofesta.tv`.[5]
5. Registrare il seriale software Cast della TV di test e attendere lo stato **Ready for Testing**.
6. Riavviare il dispositivo Cast/Google TV.
7. Configurare ogni sender con lo stesso Receiver Application ID.
8. Usare il namespace `urn:x-cast:it.fotofesta.tv` e il formato descritto in [CAST_PROTOCOL.md](CAST_PROTOCOL.md).
9. Prima della distribuzione, pubblicare l’applicazione nella Cast Console.

Il receiver è già dichiarato nel manifest tramite `com.google.android.gms.cast.tv.action.LAUNCH`, `RECEIVER_OPTIONS_PROVIDER_CLASS_NAME` e `CastReceiverContext`. Il protocollo valida versione, quantità di foto e HTTPS; non deve ricevere token Google.

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

Non aggiungere `.jks`, password o `local.properties` al repository. La soluzione preferita per il Play Store è **Play App Signing**, usando la chiave locale come upload key.

## 6. Checklist prima della pubblicazione

| Area | Azione |
| --- | --- |
| Google Foto | Verifica OAuth completata e account di test fornito ai reviewer |
| Cast | Receiver Application ID pubblicato; dispositivi e sender testati |
| Privacy | Informativa definitiva su URL pubblico e Data Safety coerente |
| Sicurezza | Nessun segreto server, token o keystore nel repository |
| Store | AAB firmato, icona 512×512, banner TV 320×180 e screenshot reali |
| TV | Test D-pad, Back, standby/ripresa, rete assente, USB rimosso |
| Accessibilità | Test TalkBack, ordine focus, contrasto e riduzione movimento |
| Prestazioni | Prova con almeno 1.000 foto USB e spazio cache ridotto |
| Supporto | Email di assistenza e procedura di cancellazione dati |

## Riferimenti

[1]: https://developers.google.com/photos/support/updates "Updates to the Google Photos APIs"
[2]: https://developers.google.com/photos/overview/authorization "Google Photos API authorization scopes"
[3]: https://developers.google.com/identity/protocols/oauth2/limited-input-device "OAuth 2.0 for TV and Limited-Input Device Applications"
[4]: https://developers.google.com/cast/docs/overview "Google Cast overview"
[5]: https://developers.google.com/cast/docs/registration "Google Cast application registration"
