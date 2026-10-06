# Protocollo Google Cast di FotoFesta TV

**Versione:** 1  
**Namespace:** `urn:x-cast:it.fotofesta.tv`

FotoFesta TV usa **Cast Connect**. Un’app sender Android, iOS o Web registrata con lo stesso Receiver Application ID apre l’app nativa sul televisore e invia messaggi UTF-8 JSON nel namespace indicato. La sessione Cast fornisce discovery, pairing e canale autenticato a livello di dispositivo. Il protocollo applicativo non deve mai trasportare password, token OAuth Google o URL contenenti credenziali.

## Busta comune

Ogni comando contiene `v`, che deve essere `1`, e `type`.

```json
{"v":1,"type":"PLAY"}
```

## Comandi

| `type` | Campi aggiuntivi | Effetto |
| --- | --- | --- |
| `PLAY` | Nessuno | Avvia o riprende lo slideshow. |
| `PAUSE` | Nessuno | Mette in pausa slideshow e musica. |
| `NEXT` | Nessuno | Mostra la foto successiva. |
| `PREVIOUS` | Nessuno | Mostra la foto precedente. |
| `SETTINGS` | `settings` | Aggiorna le impostazioni valide. |
| `LOAD` | `photos`, `settings` opzionale | Carica da 1 a 500 foto HTTPS e avvia lo slideshow. |

### Impostazioni

```json
{
  "intervalSeconds": 8,
  "transition": "FADE",
  "fitMode": "CONTAIN",
  "shuffle": false,
  "reducedMotion": false,
  "showClock": true,
  "musicVolume": 0.35
}
```

`intervalSeconds` è limitato a 2–120. `transition` accetta `FADE`, `SLIDE` o `ZOOM`. `fitMode` accetta `CONTAIN` o `CROP`. Valori non validi ricadono su impostazioni conservative.

### Caricamento

```json
{
  "v": 1,
  "type": "LOAD",
  "photos": [
    {"id":"p-001","title":"Roma","url":"https://cdn.example.org/p-001.jpg"}
  ],
  "settings": {"intervalSeconds":10,"transition":"FADE"}
}
```

Gli URL devono essere HTTPS, non devono includere bearer token e devono essere accessibili dal televisore. Per Google Foto è preferibile avviare il Picker direttamente sul TV: le `baseUrl` della Picker API richiedono autorizzazione, scadono e non vanno inoltrate ai sender.

## Sicurezza e compatibilità

Il receiver rifiuta versioni diverse da `1`, messaggi oltre 1 MB, URL non HTTPS, URL con credenziali incorporate, host locali/privati, porte diverse da 443 e liste oltre 500 elementi. Titoli e identificatori hanno lunghezza limitata. Il receiver deve essere registrato nella **Google Cast SDK Developer Console** e associato al package `it.fotofesta.tv`. Il sender deve usare il Receiver Application ID risultante. In produzione, validare anche tipo MIME, dimensione e timeout delle risorse remote sul livello di rete.
