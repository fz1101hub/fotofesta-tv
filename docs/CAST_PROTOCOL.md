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
  "musicVolume": 0.35,
  "kenBurns": false,
  "sleepTimerMinutes": 0
}
```

Tutti i campi sono facoltativi: `SETTINGS` è un aggiornamento parziale e i campi assenti, di tipo errato o con valori non riconosciuti lasciano invariata l’impostazione corrente della TV. `intervalSeconds` è limitato a 2–120, `musicVolume` a 0–1 e `sleepTimerMinutes` a 0–240 (0 disattiva la pausa automatica). `kenBurns` attiva zoom e spostamento lenti su ogni foto, ignorati se `reducedMotion` è attivo. `transition` accetta `FADE`, `SLIDE` o `ZOOM`; `fitMode` accetta `CONTAIN` o `CROP` (maiuscole e minuscole indifferenti).

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

`id` e `title` sono facoltativi; il receiver rende comunque univoco l’identificatore di ogni foto, anche se il sender ripete lo stesso `id`. Gli URL devono essere HTTPS, non devono includere bearer token e devono essere accessibili dal televisore. Per Google Foto è preferibile avviare il Picker direttamente sul TV: le `baseUrl` della Picker API richiedono autorizzazione, scadono e non vanno inoltrate ai sender.

## Sicurezza e compatibilità

Il receiver rifiuta versioni diverse da `1`, messaggi oltre 1 MB, URL non HTTPS, URL con credenziali incorporate, porte diverse da 443 e liste oltre 500 elementi. Sono rifiutati anche gli host locali o privati: `localhost`, domini `.local`/`.internal`/`.home.arpa`, IPv4 di loopback, privati, link-local, CGNAT (`100.64.0.0/10`), multicast e riservati, IPv6 di loopback, link-local, ULA (`fc00::/7`) e indirizzi IPv4 mappati in IPv6. Gli indirizzi numerici in forme ambigue (ottale come `0177.0.0.1`, esadecimale, intero singolo come `2130706433`) sono rifiutati perché resolver diversi li interpretano in modo diverso. I messaggi rifiutati vengono ignorati e registrati nel log. I comandi ricevuti prima che l’interfaccia sia pronta, ad esempio un `LOAD` inviato subito dopo l’avvio tramite Cast Connect, vengono conservati (fino a 64) ed eseguiti appena l’app è attiva. Titoli e identificatori hanno lunghezza limitata. Il receiver deve essere registrato nella **Google Cast SDK Developer Console** e associato al package `it.fotofesta.tv`. Il sender deve usare il Receiver Application ID risultante. La validazione degli URL è sintattica e non protegge dal DNS rebinding: in produzione validare anche tipo MIME, dimensione e timeout delle risorse remote sul livello di rete. La `MediaSession` dell’app è collegata a Cast Connect, quindi anche i normali comandi multimediali del sender (play, pausa, successiva, precedente) controllano lo slideshow.
