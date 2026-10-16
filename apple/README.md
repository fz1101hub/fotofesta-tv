# FotoFesta TV per Apple TV

Versione tvOS dell'app, scritta in Swift e SwiftUI, pensata per l'App Store. Condivide con l'app Android il comportamento e il flusso Google Foto.

## Funzioni

| Funzione | Apple TV | Note rispetto ad Android TV |
| --- | --- | --- |
| Foto demo | Sì | |
| Google Foto (QR + Picker API) | Sì | Stesso client OAuth "TVs and Limited Input devices" |
| Foto degli invitati (QR, pagina web servita dalla TV) | Sì | Stessa pagina per i telefoni (`shared/assets/guest/index.html`), server su Network.framework |
| Cartelle USB | No | tvOS non dà alle app accesso a dischi esterni |
| Slideshow: durata, transizioni, adattamento, ordine casuale, orologio | Sì | |
| Movimento lento (Ken Burns) e pausa automatica | Sì | |
| Musica di sottofondo | No | tvOS non permette di scegliere file audio locali |
| Salvaschermo di sistema | No | tvOS non accetta salvaschermi di terze parti |
| Trova la coppia e Foto misteriosa | Sì | |
| Italiano, inglese, francese, spagnolo e tedesco | Sì | `apple/scripts/strings.py` (inglese e italiano) e `apple/scripts/translations/<lingua>.json` generano `Localizable.strings` e `InfoPlist.strings` |

Le foto di Google Foto non vengono copiate sulla Apple TV: si caricano da Google alla risoluzione richiesta, con una sessione di rete senza cache su disco, e restano solo in memoria. In UserDefaults resta un piccolo elenco con la sessione Picker e gli identificativi delle foto; quando Google chiude la sessione (la data è mostrata a fine collegamento) l'app torna alle foto demo e chiede di sceglierle di nuovo. Una selezione è limitata a 1000 foto, perché su tvOS le preferenze hanno spazio ridotto. I token restano nel Portachiavi, che sopravvive alla disinstallazione: al primo avvio dopo una nuova installazione l'app cancella i token rimasti.

Le foto degli invitati arrivano dai telefoni sulla rete di casa, come su Android ([CONFIGURATION.md](../docs/CONFIGURATION.md#3-foto-degli-invitati)). Restano in RAM fino a 64 MB, poi le più vecchie passano in `Caches/guest_photos`, che non supera 300 MB né il 10% dello spazio libero. “Termina la festa” cancella tutto. Il server è acceso solo con l'app in primo piano e accetta solo indirizzi della rete locale. La password della Wi‑Fi per il QR resta nel Portachiavi.

AirPlay non è un modo per mandare foto alla festa: lo gestisce tvOS sopra l'app, e le app non possono riceverlo. Le foto trasmesse coprono lo slideshow e non entrano nella raccolta. La schermata della festa suggerisce di limitare l'accesso ad AirPlay alle persone di casa (Impostazioni › AirPlay e HomeKit).

## Struttura

```
apple/
├── project.yml                 progetto XcodeGen (target tvOS 17)
├── Config/                     App.xcconfig + Secrets.example.xcconfig
├── FotoFestaCore/              pacchetto Swift senza UI, con test XCTest
│   ├── Sources/FotoFestaCore/  modelli, ordine di riproduzione, Ken Burns, OAuth, Picker, importazione,
│   │                           archivio e server delle foto degli invitati
│   └── Tests/                  test con un server Google finto e con il server degli invitati su loopback
├── FotoFestaTV/
│   ├── Sources/                app SwiftUI (AppModel, GuestParty, schermate, Portachiavi, caricamento immagini)
│   └── Resources/              asset catalog, foto demo, testi it/en, PrivacyInfo.xcprivacy
├── fastlane/                   TestFlight, scheda e invio ad App Store Connect
└── scripts/strings.py          testi localizzati e controllo delle chiavi
```

## Compilare ed eseguire

Serve un Mac con **Xcode 26 o successivo**, l'unica versione accettata da App Store Connect da aprile 2026.

```bash
brew install xcodegen
cd apple
cp Config/Secrets.example.xcconfig Config/Secrets.xcconfig   # Team ID ed eventuale client Google (vuoto = niente Google Foto)
xcodegen generate
(cd FotoFestaCore && swift test)
open FotoFestaTV.xcodeproj                                    # schema FotoFestaTV, destinazione Apple TV
```

Per modificare i testi: aggiorna `scripts/strings.py`, esegui `python3 scripts/strings.py` (che verifica anche le chiavi usate nel codice) e committa i `.strings` generati. Per la grafica: `python3 ../scripts/make_store_assets.py`.

La pubblicazione è descritta in [docs/PUBLISHING.md](../docs/PUBLISHING.md).

## Stato di verifica

Il workflow `.github/workflows/apple.yml` gira su un runner macOS 15 con Xcode 26.3 (SDK tvOS 26): i 68 test XCTest del nucleo passano e l'app si compila per tvOS senza firma, senza errori né avvisi Swift. Con un repository privato i minuti macOS sono a pagamento. La logica è la stessa dell'app Android, coperta dai test JVM.

Con `[screenshots]` nel messaggio dell'ultimo commit (o avviando il workflow a mano) la CI apre anche l'app nel simulatore Apple TV in italiano e fotografa le schermate principali: home, slideshow, impostazioni, giochi, festa. Durante la festa lo script `scripts/screenshots.sh` manda alcune foto come farebbe il telefono di un invitato, per mostrare slideshow, miniature e approvazione. Le PNG a piena risoluzione si scaricano dall'artifact `FotoFestaTV-screenshots` e vanno bene anche per App Store Connect. Il passo fallisce se l'app si chiude da sola in una schermata. Gli scenari sono in `ScreenshotScenario.swift` e sono attivi solo nelle build di debug.

Prima del primo invio servono ancora tre cose:
- archiviare e firmare l'app in Xcode 26 con il proprio Team ID;
- provarla nel simulatore e su una Apple TV reale;
- controllare in Xcode l'asset catalog dell'icona a livelli.
