# FotoFesta TV per Apple TV

Versione tvOS dell'app, scritta in Swift e SwiftUI, pensata per l'App Store. Condivide con l'app Android il comportamento, il flusso Google Foto e il formato della cache.

## Funzioni

| Funzione | Apple TV | Note rispetto ad Android TV |
| --- | --- | --- |
| Foto demo | Sì | |
| Google Foto (QR + Picker API) | Sì | Stesso client OAuth "TVs and Limited Input devices" |
| Cartelle USB | No | tvOS non dà alle app accesso a dischi esterni |
| Slideshow: durata, transizioni, adattamento, ordine casuale, orologio | Sì | |
| Movimento lento (Ken Burns) e pausa automatica | Sì | |
| Musica di sottofondo | No | tvOS non permette di scegliere file audio locali |
| Salvaschermo di sistema | No | tvOS non accetta salvaschermi di terze parti |
| Google Cast | No | Su Apple TV si usa AirPlay del sistema |
| Memory e Foto misteriosa | Sì | |
| Italiano e inglese | Sì | `apple/scripts/strings.py` genera i file `.strings` |

Su tvOS lo spazio locale ampio disponibile è la cartella **Caches**, che il sistema può svuotare quando l'app non è in uso. In quel caso l'app torna alle foto demo e invita a importare di nuovo le foto da Google Foto. I token restano nel Portachiavi. Il Portachiavi sopravvive alla disinstallazione: al primo avvio dopo una nuova installazione l'app cancella i token rimasti.

## Struttura

```
apple/
├── project.yml                 progetto XcodeGen (target tvOS 17)
├── Config/                     App.xcconfig + Secrets.example.xcconfig
├── FotoFestaCore/              pacchetto Swift senza UI, con test XCTest
│   ├── Sources/FotoFestaCore/  modelli, ordine di riproduzione, Ken Burns, OAuth, Picker, importazione
│   └── Tests/                  test con un server Google finto
├── FotoFestaTV/
│   ├── Sources/                app SwiftUI (AppModel, schermate, Portachiavi, caricamento immagini)
│   └── Resources/              asset catalog, foto demo, testi it/en, PrivacyInfo.xcprivacy
├── fastlane/                   TestFlight, scheda e invio ad App Store Connect
└── scripts/strings.py          testi localizzati e controllo delle chiavi
```

## Compilare ed eseguire

Serve un Mac con **Xcode 26 o successivo**, l'unica versione accettata da App Store Connect da aprile 2026.

```bash
brew install xcodegen
cd apple
cp Config/Secrets.example.xcconfig Config/Secrets.xcconfig   # Team ID ed eventuale client Google
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
