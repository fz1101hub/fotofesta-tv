package it.fotofesta.tv.network

import it.fotofesta.tv.guests.GuestUploadServer
import java.net.InetAddress
import java.security.MessageDigest

/** Testi della pagina sul telefono, in una lingua. */
data class PairingTexts(
    val language: String,
    val title: String,
    val steps: String,
    val label: String,
    val send: String,
    val received: String,
    val invalid: String,
    val expired: String,
)

/**
 * Pagina con un solo campo che la TV serve sulla rete di casa mentre è aperta la schermata che la
 * mostra: il telefono inquadra il QR, incolla un link e la TV lo riceve. Così un link lungo non si
 * scrive con il telecomando. Vuole la chiave del QR e accetta solo moduli piccoli; limiti e
 * controllo dell'intestazione Host sono quelli di [LocalHttpServer]. Dopo [stop] nessun link viene
 * più consegnato a [onLink].
 */
class LinkPairingServer(
    private val key: String,
    private val texts: (acceptLanguage: String?) -> PairingTexts,
    readTimeoutMillis: Int = LocalHttpServer.READ_TIMEOUT_MS,
    deadlineMillis: Long = LocalHttpServer.DEADLINE_MS,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Chiamato con il testo incollato; true se è un link valido. */
    private val onLink: (String) -> Boolean,
) : LocalHttpServer.Handler {
    private val server = LocalHttpServer("link-pairing", MAX_FORM_BYTES, this, readTimeoutMillis, deadlineMillis, nowMillis)

    /** Avvia la pagina su [address] e restituisce la porta. */
    fun start(address: InetAddress, port: Int = 0): Int = server.start(address, port)

    /** Chiude la porta e le connessioni aperte: da qui in poi [onLink] non viene più chiamato. */
    fun stop() = server.stop()

    /** Ferma anche i thread: dopo non si può più riavviare. */
    fun close() = server.close()

    override fun handle(request: GuestUploadServer.Request, body: LocalHttpServer.Body): LocalHttpServer.Response {
        val text = texts(request.headers["accept-language"])
        return when {
            request.path == "/" && request.method == "GET" ->
                if (keyMatches(request.query["k"])) page(200, text, null, form = true) else page(403, text, text.expired, form = false)
            request.path == "/link" && request.method == "POST" -> when (body) {
                // Le pagine di errore non ripetono il modulo: contengono la chiave del QR e qui non è stata provata.
                LocalHttpServer.Body.Missing -> page(411, text, text.invalid, form = false)
                LocalHttpServer.Body.TooLarge -> page(413, text, text.invalid, form = false)
                is LocalHttpServer.Body.Bytes -> {
                    val form = GuestUploadServer.parseForm(String(body.bytes, Charsets.UTF_8))
                    when {
                        !keyMatches(form["k"]) -> page(403, text, text.expired, form = false)
                        onLink(form["link"].orEmpty()) -> page(200, text, text.received, form = false, outcome = "ok")
                        else -> page(400, text, text.invalid, form = true, outcome = "error")
                    }
                }
            }
            else -> page(404, text, text.expired, form = false)
        }
    }

    override fun error(status: Int, acceptLanguage: String?): LocalHttpServer.Response {
        val text = texts(acceptLanguage)
        return page(status, text, if (status == 403 || status == 421) text.expired else null, form = false)
    }

    private fun keyMatches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), key.toByteArray())

    private fun page(status: Int, text: PairingTexts, message: String?, form: Boolean, outcome: String? = null): LocalHttpServer.Response =
        LocalHttpServer.Response.html(status, html(text, message, form, outcome))

    private fun html(text: PairingTexts, message: String?, form: Boolean, outcome: String?): String = buildString {
        append("<!doctype html><html lang=\"").append(text.language).append("\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>").append(escape(text.title)).append("</title><style>")
        append("body{font:17px/1.5 system-ui,-apple-system,sans-serif;margin:0;padding:28px 20px;background:#0d0f13;color:#fff}")
        append("main{max-width:560px;margin:0 auto}h1{font-size:1.5rem;margin:0 0 12px}p{color:#c8cdd6}")
        append("label{display:block;font-weight:600;margin:20px 0 8px}")
        append("textarea{box-sizing:border-box;width:100%;min-height:96px;font:16px/1.4 inherit;padding:12px;border-radius:12px;border:1px solid #5b6270;background:#16191f;color:#fff}")
        append("button{margin-top:16px;width:100%;font:600 17px inherit;padding:14px;border:0;border-radius:999px;background:#fff;color:#0d0f13}")
        append(".ok{color:#7fe0a0;font-weight:600}.error{color:#ff9a8f;font-weight:600}")
        append("</style></head><body><main><h1>").append(escape(text.title)).append("</h1>")
        if (message != null) {
            append("<p class=\"").append(if (outcome == "ok") "ok" else if (outcome == "error") "error" else "").append("\">")
            append(escape(message)).append("</p>")
        }
        if (form) {
            append("<p>").append(escape(text.steps)).append("</p>")
            append("<form method=\"post\" action=\"/link\"><input type=\"hidden\" name=\"k\" value=\"").append(escape(key)).append("\">")
            append("<label for=\"link\">").append(escape(text.label)).append("</label>")
            append("<textarea id=\"link\" name=\"link\" required autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\"></textarea>")
            append("<button type=\"submit\">").append(escape(text.send)).append("</button></form>")
        }
        append("</main></body></html>")
    }

    companion object {
        private const val MAX_FORM_BYTES = 4_096

        fun escape(text: String): String = buildString {
            for (char in text) {
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(char)
                }
            }
        }

        /** Lingua della pagina dal primo codice di Accept-Language che l'app conosce; inglese altrimenti. */
        fun language(acceptLanguage: String?, known: Set<String>): String =
            acceptLanguage.orEmpty().split(',')
                .map { it.substringBefore(';').trim().substringBefore('-').lowercase() }
                .firstOrNull { it in known } ?: "en"
    }
}
