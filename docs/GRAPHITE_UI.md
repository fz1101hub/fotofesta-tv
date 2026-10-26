# Graphite Edition — UI/UX 2.0

## Direzione

FotoFesta TV adotta un linguaggio visivo monocromatico: il colore appartiene
alle fotografie, non all'interfaccia. Schermate principali in nero grafite
(`#0B0B0D`), superfici (`#19191D`), superfici evidenziate (`#29292F`),
testo primario (`#FAFAFA`) e secondario (`#A6A6AD`).

## Flusso principale

1. **Home:** anteprima della fotografia attiva, nome della sorgente, CTA
   «Avvia slideshow» con focus iniziale (quando sono presenti foto).
2. **Raccolte:** sorgenti demo, Google Foto e USB. La scelta USB continua a
   usare il selettore cartelle di Android, Google Foto il flusso QR esistente.
3. **Slideshow:** fotografie a schermo pieno; i controlli restano visibili
   quando si mette in pausa e scompaiono dopo 4 secondi quando in riproduzione.
4. **Impostazioni:** sezioni Foto e transizioni, Musica, Accessibilità,
   Account e privacy.
5. **Google Foto:** passaggi espliciti di collegamento, selezione e preparazione.
6. **Giochi:** anteprima fotografica per Memory e Foto misteriosa.

## Interazioni D-pad

- Home: focus su «Avvia slideshow» o su «Gestisci foto» se non ci sono foto.
- Slideshow, overlay nascosto: OK = pausa + mostra controlli; destra/sinistra
  cambiano fotografia; giù mostra i controlli.
- Slideshow, overlay visibile: D-pad naviga i pulsanti, Back torna alla Home.
- Impostazioni: focus iniziale sulla prima categoria; i pulsanti consentono
  di cambiare opzione senza uscire dalla schermata.
- «Riduci movimento» elimina la scala di focus nei componenti aggiornati e
  mantiene disabilitate le transizioni dello slideshow.
- «Contrasto elevato» rafforza i gradienti sovrapposti alle fotografie.
- «Disconnetti Google Foto» richiede conferma.

## Componenti principali

- `FotoFestaTheme.kt`: design tokens Graphite.
- `TvComponents.kt`: `TvButton`, `SourceCard`, `AppHeader`, `SettingRow`.
- `HomeScreen.kt`, `SourcesScreen.kt`, `SlideshowScreen.kt`:
  esperienza foto-first.
- `SettingsScreen.kt`, `GoogleConnectScreen.kt`, `GamesScreen.kt`:
  schermate secondarie coerenti.
- `SettingsRepository.kt`, `AppModels.kt`, `CastBridge.kt`:
  persistenza e lettura della nuova opzione `highContrast`.

## Verifica prima del merge

Compilare e testare:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Test manuali su Android TV / Google TV:

- Home su display 1080p e 4K, senza clipping e con overscan.
- D-pad tra Home, Raccolte, Impostazioni, Google Foto e Giochi.
- Stato senza fotografie e stato con demo, USB e Google Foto.
- Focus dopo Back, dopo selezione USB e dopo la chiusura delle impostazioni.
- Slideshow: riproduzione automatica, pausa, skip, overlay e tasti media.
- Riduzione movimento e contrasto elevato.
- QR con credenziali OAuth reali, rete non disponibile, retry e progress.
- Disconnessione Google Foto: conferma, cancellazione e ritorno alla demo.
- Memory, Foto misteriosa, audio e Cast Connect.

## Limiti

Le verifiche in CI non sostituiscono una prova su dispositivo TV reale.
La PR non incorpora nuove credenziali OAuth/Cast, né cambia i permessi di accesso
alle fotografie. Evitare di aggiornare il resoconto della build 1.0 come se
certificasse la nuova Graphite Edition.
