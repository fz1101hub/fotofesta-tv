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
| Memory e Foto misteriosa | Sì | |
| Italiano e inglese | Sì | `apple/scripts/strings.py` genera i file `.strings` |

Le foto di Google Foto non vengono copiate sulla Apple TV: si caricano da Google alla risoluzione richiesta, con una sessione di rete senza cache su disco, e restano solo in memoria. In UserDefaults resta un piccolo elenco con la sessione Picker e gli identificativi delle foto; quando Google chiude la sessione (la data è mostrata a fine collegamento) l'app torna alle foto demo e chiede di sceglierle di nuovo. Una selezione è limitata a 1000 foto, perché su tvOS le preferenze hanno spazio ridotto. I token restano nel Portachiavi, che sopravvive alla disinstallazione: al primo avvio dopo una nuova installazione l'app cancella i token rimasti.

Le foto degli invitati arrivano dai telefoni sulla rete di casa, come su Android ([CONFIGURATION.md](../docs/CONFIGURATION.md#3-foto-degli-invitati)). Restano in RAM fino a 64 MB, poi le più vecchie passano in `Caches/guest_photos`, che non supera 300 MB né il 10% dello spazio libero. “Termina la festa” cancella tutto. Il server è acceso solo con l'app in primo piano e accetta solo indirizzi della rete locale. La password della Wi‑Fi per il QR resta nel Portachiavi.

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

Il codice è stato scritto in un ambiente senza toolchain Swift, quindi **non è ancora stato compilato**. La logica è la stessa dell'app Android, dove è coperta da test JVM che passano. I test XCTest del nucleo replicano gli stessi casi. Il workflow `.github/workflows/apple.yml` esegue `swift test` e compila l'app per tvOS su un runner macOS; con un repository privato i minuti macOS sono a pagamento.

Prima del primo invio servono quattro cose:
- generare il progetto ed eseguire `swift test`;
- compilare l'app in Xcode e correggere eventuali errori o avvisi;
- provarla nel simulatore e su una Apple TV reale;
- controllare in Xcode l'asset catalog dell'icona a livelli.
