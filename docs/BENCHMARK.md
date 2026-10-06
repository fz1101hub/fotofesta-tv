# Benchmark delle esperienze foto su Android TV

Il confronto ha considerato **PixFolio**, **Fotoo**, **Carousel**, **TV Photo** e l’esperienza ufficiale **Google Foto su TV**. La selezione copre app orientate a Google Foto, cornici multi-cloud, soluzioni local-first e il riferimento di piattaforma.

## Sintesi comparativa

| Prodotto | Punto di forza | Limite principale rilevante |
| --- | --- | --- |
| PixFolio | Slideshow ricco, transizioni, musica, Android TV, Chromecast e USB.[1] | Dopo i cambiamenti Photos API richiede una selezione/importazione locale e non può offrire aggiornamento automatico dell’intera libreria.[2] |
| Fotoo | Molte sorgenti dichiarate: Google Foto, Drive, Dropbox, OneDrive, locale e SMB; include musica ed effetti.[3] | Documentazione pubblica meno precisa su Cast, D-pad, prezzi e comportamento operativo. |
| Carousel | Approccio local-first con USB, SMB, Immich e upload QR senza account.[4] | Non documenta musica, protocollo Cast o un catalogo dettagliato di transizioni. |
| TV Photo | Esperienza TV locale con album, slideshow, screensaver e controlli da telecomando.[5] | Ultimo aggiornamento Play indicato nel 2021; nessuna musica o sorgente cloud documentata. |
| Google Foto su TV | Modalità Ambient nativa, album, Ricordi, ricerca e Cast ufficiale.[6] | Dipendenza da account/rete; musica e transizioni personalizzabili non sono documentate. |

## Decisioni adottate in FotoFesta TV

FotoFesta TV non richiede il login Google quando l’utente usa USB. Il focus è sempre visibile e l’intero flusso è percorribile con D-pad. Le transizioni sono nominate e includono una modalità senza movimento. Musica, cache e disconnessione hanno stati espliciti. Il protocollo Cast è documentato e distinto dalla mera compatibilità con un dispositivo Chromecast with Google TV.

La privacy è local-first. Non sono presenti SDK pubblicitari o analytics. I metadati sensibili non vengono mostrati per impostazione predefinita. I token sono protetti da Android Keystore e gli URL Google autorizzati non attraversano il protocollo Cast.

## Elementi differenzianti

| Differenziatore | Beneficio |
| --- | --- |
| USB senza account | Funzionamento offline e minimo trattamento dati |
| Picker Google da QR | Nessuna password digitata con telecomando e consenso granulare |
| Cache temporanea 4K automatica | Slideshow stabile oltre la scadenza di 60 minuti delle `baseUrl` |
| Protocollo Cast v1 | Base interoperabile per futuri sender Android, iOS e Web |
| Giochi con foto reali | Esperienza familiare non presente nelle app confrontate |
| Riduzione movimento | Migliore accessibilità e comfort visivo |
| Data policy trasparente | Elimina ambiguità tra funzionamento locale, cache e cloud |

## Anti-pattern evitati

Non viene chiamata “sincronizzazione” una selezione manuale. Non viene promesso accesso a tutta la libreria Google Foto. Non viene richiesto Google per una sorgente locale. Il prezzo non è codificato nell’esperienza e non esistono paywall nascosti. Non vengono esposti GPS, nomi rete o altre informazioni contestuali nello slideshow. Infine, l’app non presume che “Chromecast with Google TV” equivalga al protocollo Google Cast: Cast Connect è implementato separatamente.

## Riferimenti

[1]: https://play.google.com/store/apps/details?id=com.snapwood.photos2 "PixFolio - Google Foto on Google Play"
[2]: https://www.snapwoodapps.com/google2025/ "PixFolio Google Photos 2025 changes"
[3]: https://play.google.com/store/apps/details?id=com.bo.fotoo "Fotoo - Photo Frame Slideshow on Google Play"
[4]: https://carousel-screensaver.web.app/ "Carousel Android TV Screensaver"
[5]: https://play.google.com/store/apps/details?id=com.csanad.tvphoto "TV Photo on Google Play"
[6]: https://support.google.com/photos/answer/16996534 "Use Google Photos on your TV"
