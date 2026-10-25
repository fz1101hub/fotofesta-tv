package it.fotofesta.tv.guests

/**
 * Capsula del tempo sulla chiavetta: una pagina HTML che si apre con qualunque browser, accanto
 * alla cartella delle foto. Fino alla data scelta mostra il conto alla rovescia (si può sbirciare);
 * poi foto, dediche, propositi e chi è arrivato. Nessuna risorsa esterna: la pagina deve
 * funzionare tra anni, senza rete.
 */
object TimeCapsule {
    data class Photo(val file: String, val sender: String?)
    data class Note(val text: String, val name: String?)

    /** Testi nella lingua della TV: {n} è il numero di giorni, {date} una data. */
    data class Texts(
        val kicker: String,
        val sealedOn: String,
        val opensOn: String,
        val daysOne: String,
        val daysOther: String,
        val peek: String,
        val photos: String,
        val messages: String,
        val wishes: String,
        val arrivals: String,
    )

    fun html(
        language: String,
        title: String,
        sealedText: String,
        openText: String,
        openAtMillis: Long,
        photos: List<Photo>,
        messages: List<Note>,
        wishes: List<Note>,
        arrivals: List<String>,
        texts: Texts,
    ): String {
        val data = buildString {
            append("{\"title\":").append(json(title))
            append(",\"sealed\":").append(json(sealedText))
            append(",\"open\":").append(json(openText))
            append(",\"openAt\":").append(openAtMillis)
            append(",\"photos\":").append(photos.joinToString(",", "[", "]") { "{\"file\":${json(it.file)},\"sender\":${it.sender?.let(::json) ?: "null"}}" })
            append(",\"messages\":").append(notes(messages))
            append(",\"wishes\":").append(notes(wishes))
            append(",\"arrivals\":").append(arrivals.joinToString(",", "[", "]") { json(it) })
            append(",\"t\":{")
            append(
                listOf(
                    "kicker" to texts.kicker, "sealedOn" to texts.sealedOn, "opensOn" to texts.opensOn,
                    "daysOne" to texts.daysOne, "daysOther" to texts.daysOther, "peek" to texts.peek,
                    "photos" to texts.photos, "messages" to texts.messages, "wishes" to texts.wishes, "arrivals" to texts.arrivals,
                ).joinToString(",") { (key, value) -> "\"$key\":${json(value)}" }
            )
            append("}}")
        }
        // Nessun "<" dentro lo script: un nome come "</script>" non può chiudere il blocco dei dati.
        val safeData = data.replace("<", "\\u003c")
        // Segnaposto sostituiti sul modello, mai sui testi già inseriti (un nome può contenerli).
        val lang = language.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "en" }
        return TEMPLATE.substringBefore("__LANG__") + lang +
            TEMPLATE.substringAfter("__LANG__").substringBefore("__TITLE__") + escapeHtml(title) +
            TEMPLATE.substringAfter("__TITLE__").substringBefore("__DATA__") + safeData +
            TEMPLATE.substringAfter("__DATA__")
    }

    private fun notes(list: List<Note>) =
        list.joinToString(",", "[", "]") { "{\"text\":${json(it.text)},\"name\":${it.name?.let(::json) ?: "null"}}" }

    private fun json(value: String) = GuestUploadServer.jsonString(value)

    private fun escapeHtml(value: String) = buildString {
        value.forEach { char ->
            when (char) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(char)
            }
        }
    }

    private const val TEMPLATE = """<!doctype html>
<html lang="__LANG__">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__</title>
<style>
  :root { --bg: #0d0f13; --card: #1b1e25; --raised: #262a33; --fg: #f4f1ec; --muted: #a7a9b4; --accent: #ff7a57; --pink: #ff4f9a; --violet: #8b6cff; --gold: #ffc145; }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--fg); font: 17px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  main { max-width: 1100px; margin: 0 auto; padding: 40px max(18px, env(safe-area-inset-left)) 64px; }
  .kicker { font-size: .8rem; font-weight: 800; letter-spacing: .14em; text-transform: uppercase; color: var(--gold); }
  h1 { font-size: clamp(2rem, 6vw, 3.4rem); line-height: 1.05; margin: .3rem 0 .4rem; text-wrap: balance; }
  h2 { font-size: 1.4rem; margin: 2.4rem 0 1rem; }
  .muted { color: var(--muted); }
  .lock { min-height: 80vh; display: grid; place-content: center; text-align: center; gap: 14px; }
  .lock .glass { font-size: 4rem; }
  .lock .days { font-size: clamp(2.6rem, 9vw, 5rem); font-weight: 900; background: linear-gradient(120deg, var(--accent), var(--pink), var(--violet)); -webkit-background-clip: text; background-clip: text; color: transparent; }
  button { justify-self: center; margin-top: 10px; padding: 14px 26px; border: 0; border-radius: 16px; background: var(--raised); color: var(--fg); font: 700 1rem system-ui, sans-serif; cursor: pointer; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; }
  figure { margin: 0; background: var(--card); border-radius: 16px; overflow: hidden; }
  figure img { display: block; width: 100%; aspect-ratio: 4 / 3; object-fit: cover; }
  figcaption { padding: 8px 12px; font-size: .9rem; color: var(--muted); }
  .notes { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 14px; }
  .note { background: var(--card); border-radius: 16px; padding: 16px 18px; border-left: 4px solid var(--pink); }
  .note p { margin: 0 0 .4rem; font-size: 1.1rem; }
  .wishes .note { border-left-color: var(--violet); }
  .names { display: flex; flex-wrap: wrap; gap: 8px; padding: 0; list-style: none; }
  .names li { background: var(--raised); border-radius: 999px; padding: 6px 14px; }
</style>
</head>
<body>
<main id="app"></main>
<script id="capsule" type="application/json">__DATA__</script>
<script>
const data = JSON.parse(document.getElementById("capsule").textContent);
const t = data.t;
const app = document.getElementById("app");
function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}
function header(parent) {
  parent.appendChild(el("div", "kicker", t.kicker));
  parent.appendChild(el("h1", null, data.title));
  parent.appendChild(el("p", "muted", t.sealedOn.replace("{date}", data.sealed)));
}
function lock() {
  const days = Math.ceil((data.openAt - Date.now()) / 86400000);
  if (days <= 0) return open();
  app.textContent = "";
  const box = el("section", "lock");
  box.appendChild(el("div", "glass", "⏳"));
  header(box);
  box.appendChild(el("div", "days", (days === 1 ? t.daysOne : t.daysOther).replace("{n}", days)));
  box.appendChild(el("p", "muted", t.opensOn.replace("{date}", data.open)));
  const peek = el("button", null, t.peek);
  peek.addEventListener("click", open);
  box.appendChild(peek);
  app.appendChild(box);
}
function notes(title, list, extra) {
  if (!list.length) return;
  app.appendChild(el("h2", null, title));
  const box = el("div", "notes " + (extra || ""));
  list.forEach((item) => {
    const card = el("div", "note");
    card.appendChild(el("p", null, item.text));
    if (item.name) card.appendChild(el("div", "muted", "— " + item.name));
    box.appendChild(card);
  });
  app.appendChild(box);
}
function open() {
  app.textContent = "";
  header(app);
  if (data.photos.length) {
    app.appendChild(el("h2", null, t.photos + " · " + data.photos.length));
    const grid = el("div", "grid");
    data.photos.forEach((photo) => {
      const figure = el("figure");
      const link = el("a");
      link.href = photo.file;
      const image = el("img");
      image.src = photo.file;
      image.loading = "lazy";
      image.alt = photo.sender || "";
      link.appendChild(image);
      figure.appendChild(link);
      if (photo.sender) figure.appendChild(el("figcaption", null, photo.sender));
      grid.appendChild(figure);
    });
    app.appendChild(grid);
  }
  notes(t.messages, data.messages);
  notes(t.wishes, data.wishes, "wishes");
  if (data.arrivals.length) {
    app.appendChild(el("h2", null, t.arrivals));
    const list = el("ul", "names");
    data.arrivals.forEach((name) => list.appendChild(el("li", null, name)));
    app.appendChild(list);
  }
}
lock();
</script>
</body>
</html>
"""
}
