# Correzioni dopo audit indipendente — 14 ottobre 2026

**Ramo:** `fix/all-audit-findings-2026-10-14`  
**Android:** versione 1.2.2, versionCode 5  
**tvOS:** versione 1.0.1, build 2  
**Stato:** modifiche presenti nei sorgenti, **build, test strumentali e collaudo non ancora verificati**. Non dichiarare l'APK Xiaomi funzionante finché non è stato installato e provato.

## Correzioni implementate

| Finding | Correzione | File |
| --- | --- | --- |
| AND-01 | La migrazione Android elimina la cache vecchia solo se la rinomina ha avuto esito positivo e l'archivio destinazione è presente | `app/src/main/java/it/fotofesta/tv/google/GooglePhotosPickerRepository.kt` |
| AND-02 | Backup `.old-*` mantenuti e recuperati all'avvio se la cache attiva manca; pulizia backup solo con cache valida | Stesso file |
| AND-03 | Google Foto: max 24 MiB per foto, controlli MIME e spazio durante streaming. Cast: ImageLoader Coil con risoluzione DNS pubblica obbligatoria, blocco redirect e limite risposta 24 MiB (anche chunked) | `GooglePhotosPickerRepository.kt`, `FotoFestaApplication.kt` |
| AND-04 | La diagnostica TV salva solo classe d'eccezione e nomi di metodi, senza messaggi/URL potenzialmente sensibili | `StartupDiagnostics.kt` |
| AND-05 | Conservate le correzioni di avvio Xiaomi 1.2.1 (launcher e MediaSession difensiva), aggiunte le protezioni di rete: **causa e risoluzione da confermare sulla TV** | `AndroidManifest.xml`, `MainActivity.kt`, `FotoFestaApplication.kt` |
| TVO-01 | Download produzione tvOS tramite `URLSession.download(for:)` su file, senza buffer 4K completo in RAM; limite 24 MiB per foto (verificato alla fine del download) | `apple/FotoFestaCore/Sources/FotoFestaCore/HTTP.swift` |
| TVO-02 | Task group con propagazione di `CancellationError`, stop di nuovi task e cleanup staging | `apple/FotoFestaCore/Sources/FotoFestaCore/PhotosPicker.swift` |
| TVO-03 | Ultima foto del ciclo casuale precedente ricordata quando si preme Precedente | `apple/FotoFestaCore/Sources/FotoFestaCore/PlaybackOrder.swift` |
| TVO-04 | Ogni nuova partita Memory incrementa `gameGeneration`; i task ritardati delle partite vecchie non modificano le carte nuove | `apple/FotoFestaTV/Sources/Views/GamesView.swift` |
| TVO-05 | La nuova importazione Google attende la cancellazione cache e revoca della disconnessione precedente (Android e Apple TV) | `FotoFestaViewModel.kt`, `apple/FotoFestaTV/Sources/AppModel.swift` |
| TVO-06 | Ricerca e recupero della cartella `.old-*` all'accesso successivo alla cache tvOS | `apple/FotoFestaCore/Sources/FotoFestaCore/PhotosPicker.swift` |
| TVO-07 | Il caricatore di immagini tvOS visualizza una foto soltanto se il suo identificativo coincide con la richiesta corrente | `apple/FotoFestaTV/Sources/ImageLoader.swift` |

## Test di regressione aggiunti

- Android `GooglePhotosPickerRepositoryTest`: fallimento migrazione, ripristino dopo interruzione, ripristino fallito che conserva il backup, risposta immagine troppo grande.
- Android `MainActivitySmokeTest` (`androidTest`): avvio con `LEANBACK_LAUNCHER` e presenza della prima schermata. Da eseguire su emulatore/TV con `./gradlew connectedDebugAndroidTest`.
- tvOS `CoreLogicTests`: tasto Precedente attraverso la frontiera tra due cicli casuali.
- tvOS `GoogleTests`: ripristino backup, annullamento di download/import in corso, rifiuto di payload oltre il limite del client HTTP.

**I test esistono nel repository ma non sono stati eseguiti qui.** Nessun report Gradle, Xcode o test su TV è disponibile per queste nuove modifiche.

## Verifiche obbligatorie prima del rilascio

1. **Android senza Actions:** caricare in Google Colab lo ZIP di questo ramo. Eseguire `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`; salvare il log e scaricare `app/build/outputs/apk/debug/app-debug.apk`.
2. **Xiaomi:** se la nuova debug key è diversa, disinstallare prima l'APK vecchio. Verificare avvio da Home e da Impostazioni > App > Apri, foto demo e seconda apertura dopo un crash. La disinstallazione rimuove preferenze e foto in cache locale, non gli originali USB/Google.
3. **Android Google Foto:** prova con rete interrotta, 1.000 foto, spazio scarso e aggiornamento interrotto a metà. Gli errori di rinomina nei test devono conservare il vecchio archivio.
4. **Android Cast:** test di URL pubblici, DNS a IP privati, redirect e contenuti oversized. Alcuni CDN che necessitano redirect vengono intenzionalmente rifiutati.
5. **Apple TV:** su macOS `cd apple/FotoFestaCore && swift test`, poi `xcodegen generate` in `apple/` e build tvOS in Xcode. Eseguire test con importazione/disconnessione sovrapposte e molti file 4K.
6. **Revisione sicurezza finale:** scanner delle dipendenze, controllo della firma e privacy di log/report, test su dispositivo reale.

## Limiti che restano

- La causa precisa del crash Xiaomi originale **non è conosciuta**. Queste difese potrebbero risolverlo o rendere visibile un errore, ma non lo dimostrano.
- Il limite di 24 MiB dei download tvOS è applicato **dopo** `URLSession.download(for:)`: evita di importare il file fuori soglia, ma la risposta temporanea può occupare spazio prima della verifica. Per una garanzia rigorosa anche durante il trasferimento occorre un delegate di progresso che annulli oltre la soglia.
- La strategia Cast rifiuta i redirect, così previene una classe di aggiramenti ma può bloccare URL legittimi.
- Non eseguiti test d'integrazione sui dispositivi, verifica dipendenze/CVE e scansione di tutta la cronologia Git.
- Le GitHub Actions non erano disponibili: non sono state usate come certificazione.

## Consegna

La presente versione è una **candidata da verificare**, non un prodotto certificato. Il ramo di base e il rapporto originale `docs/INDEPENDENT_CODE_AUDIT_2026-10-14.md` rimangono leggibili e non vengono modificati.
