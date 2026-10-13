# Risposte per Google Play Data safety e App Store Privacy

Queste risposte descrivono la build attuale (versione 1.2.0). Vanno ricontrollate a ogni nuova versione e se si aggiunge un SDK.

## Google Play – Data safety

| Domanda di Play Console | Risposta | Motivo |
| --- | --- | --- |
| L'app raccoglie o condivide uno dei tipi di dati richiesti? | **No** | Secondo la definizione di Google, “raccolta” significa trasmettere dati fuori dal dispositivo verso lo sviluppatore o terzi. Le foto e i token restano sul dispositivo; le comunicazioni con Google avvengono su richiesta dell'utente per accedere al suo stesso account e non sono una raccolta da parte dello sviluppatore. |
| I dati sono criptati in transito? | Sì (se richiesto) | Tutto il traffico è HTTPS; il traffico in chiaro è disabilitato. |
| L'utente può chiedere l'eliminazione dei dati? | Sì | “Disconnetti e cancella” in Impostazioni; cancellazione dati app; revoca dall'Account Google. |
| Pubblicità | No | Nessun SDK pubblicitario. |

Se il revisore ritiene che le foto scaricate da Google Foto vadano dichiarate, l'alternativa coerente è: *Foto e video › Foto — raccolta, non condivisa, elaborata temporaneamente: no, facoltativa, scopo: Funzionalità dell'app*.

Altre sezioni di Play Console:
- **Accesso all'app**: indicare che la modalità demo (“Prova subito”) non richiede accesso; per Google Foto fornire un account Google di test aggiunto agli utenti di test della schermata di consenso OAuth.
- **Annunci**: l'app non contiene annunci.
- **Classificazione dei contenuti**: categoria Utilità/Produttività/Altro, nessun contenuto generato da altri utenti condiviso, nessuna interazione tra utenti.
- **Pubblico di destinazione**: 13+ o 18+ (l'app non è progettata per bambini; scegliendo fasce sotto i 13 anni si applicano i requisiti Families).
- **App per Android TV**: attivare la distribuzione su Android TV in *Impostazioni avanzate › Form factor* e caricare banner TV e screenshot TV.

## App Store – Privacy dell'app (nutrition label)

| Domanda di App Store Connect | Risposta |
| --- | --- |
| Raccogli dati da questa app? | **No, non raccogliamo dati** |

Apple definisce la raccolta come trasmissione di dati fuori dal dispositivo in modo che lo sviluppatore o i suoi partner possano accedervi più a lungo di quanto serve a gestire la richiesta in tempo reale. Le foto selezionate in Google Foto vengono scaricate dal dispositivo direttamente da Google e restano sul dispositivo.

URL dell'informativa privacy (obbligatorio per entrambi gli store): pubblicare `docs/privacy/index.html` (ad esempio con GitHub Pages) dopo aver completato i campi evidenziati.
