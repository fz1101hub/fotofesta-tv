# Modello di informativa privacy per FotoFesta TV

> **Bozza operativa, non consulenza legale.** Prima della pubblicazione sostituire tutti i campi tra parentesi quadre e far verificare il testo rispetto al titolare, ai paesi, ai servizi effettivamente attivati e alle dichiarazioni Google Play Data Safety.

**Ultimo aggiornamento:** [data]  
**Titolare del trattamento:** [nome e indirizzo]  
**Contatto privacy:** [email]

## Finalità e funzionamento

FotoFesta TV consente di visualizzare fotografie scelte dall’utente su un dispositivo Android TV/Google TV. L’app può leggere una cartella locale o USB autorizzata dall’utente, riprodurre un file audio locale e recuperare fotografie selezionate tramite Google Photos Picker API. L’app non richiede un account per le funzioni locali.

## Dati trattati

| Categoria | Origine | Uso | Conservazione |
| --- | --- | --- | --- |
| URI e nomi delle foto USB | Cartella scelta dall’utente | Creazione dello slideshow e dei minigiochi | Sul dispositivo; accesso revocabile dalle impostazioni Android o rimuovendo l’app |
| File audio locale | File scelto dall’utente | Musica di sottofondo | URI sul dispositivo; nessun upload |
| Token OAuth Google | Google OAuth 2.0 | Accesso alle sole foto selezionate | Cifrati con AES-GCM e chiave Android Keystore fino a disconnessione/revoca |
| Foto Google selezionate | Google Photos Picker API | Slideshow e minigiochi | Cache privata temporanea fino a cancellazione, disconnessione o gestione cache del sistema |
| Impostazioni slideshow | Utente | Personalizzazione locale | Preferenze sul dispositivo fino alla cancellazione dati/disinstallazione |
| Messaggi Cast | Sender sulla rete/sessione Cast | Controllo slideshow | Elaborati in memoria; nessuna conservazione prevista |

## Base giuridica

Per le funzioni richieste direttamente dall’utente, il trattamento è necessario a eseguire il servizio richiesto o si basa sul consenso espresso nel flusso OAuth, secondo l’inquadramento giuridico definito dal titolare. L’utente può non collegare Google Foto e continuare a usare foto locali o USB.

## Comunicazione e trasferimenti

Le fotografie locali non vengono inviate al titolare. Quando l’utente sceglie Google Foto, il dispositivo comunica direttamente con i servizi Google tramite TLS; a tali servizi si applicano i termini e le informative Google. [Descrivere eventuali servizi aggiuntivi introdotti nella versione pubblicata.]

## Sicurezza

L’app disabilita il traffico HTTP in chiaro. I token OAuth sono cifrati con una chiave non esportabile di Android Keystore. Il protocollo Cast accetta soltanto URL HTTPS, limita il numero di elementi e non è progettato per trasportare credenziali. Non sono integrati analytics, advertising o crash reporting esterno nella versione corrente.

## Diritti e controlli dell’utente

L’utente può cancellare token e cache Google dalla schermata **Impostazioni > Disconnetti e cancella**. Può revocare l’accesso anche dal proprio Account Google. Può cancellare ogni dato locale dalle impostazioni Android o disinstallando l’app. Per esercitare gli ulteriori diritti previsti dal Regolamento generale sulla protezione dei dati, contattare [email].

## Minori

L’app non è progettata per raccogliere intenzionalmente dati di minori. Poiché le raccolte familiari possono contenere immagini di minori, il proprietario del dispositivo deve scegliere con attenzione cosa mostrare in ambienti condivisi e pubblici.

## Modifiche

Le modifiche sostanziali a questa informativa saranno comunicate tramite [canale] prima che entrino in vigore, quando richiesto.

## Riferimenti

[1]: https://commission.europa.eu/law/law-topic/data-protection/data-protection-eu_en "Legal framework of EU data protection"
[2]: https://developers.google.com/photos/apis/user-data-developer-policy "Google Photos APIs User Data and Developer Policy"
[3]: https://support.google.com/googleplay/android-developer/answer/10787469 "Google Play Data safety form guidance"
