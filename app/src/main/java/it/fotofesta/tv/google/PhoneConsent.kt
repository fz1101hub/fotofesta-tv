package it.fotofesta.tv.google

import it.fotofesta.tv.guests.GuestUploadServer
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.network.LocalHttpServer
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Come la TV ottiene il codice di autorizzazione di Google: in produzione dal telefono, con la
 * pagina servita sulla rete di casa ([LanPhoneConsent]); nei test da un oggetto finto.
 */
interface PhoneConsent : AutoCloseable {
    /**
     * Mostra al telefono la strada verso Google e aspetta il codice. [authorizationUrl] costruisce
     * l'indirizzo di Google dato quello di ritorno sulla TV; [onState] riceve l'indirizzo da mettere nel QR.
     */
    suspend fun obtainCode(authorizationUrl: (returnUrl: String) -> String, onState: (GoogleConnectState) -> Unit): String

    /** Dopo la sessione Picker: la pagina sul telefono offre il pulsante per scegliere le foto. */
    fun publishPickerUrl(url: String)

    override fun close()
}

/**
 * Pagina che la TV serve al telefono per autorizzare Google Foto (`shared/assets/guest/google.html`):
 * - `GET /?k=KEY`: la pagina, che chiede a `/start` l'indirizzo di Google e mostra il pulsante;
 * - `GET /start?k=KEY`: `{"url": …}`, l'indirizzo di Google con il ritorno a questa TV nello `state`;
 * - `GET /google?k=KEY&code=…` (o `&error=…`): ritorno dal sito; il codice va a [onCode] e il
 *   browser viene rimandato alla pagina (`#done` o `#cancelled`), così il codice sparisce dall'indirizzo;
 * - `GET /status?k=KEY`: `{"picker": …}` appena la TV ha la sessione Picker, per scegliere le foto
 *   senza inquadrare il secondo QR.
 */
class GoogleConsentPageServer(
    private val key: String,
    private val page: () -> String,
    private val authorizationUrl: (returnUrl: String) -> String,
    private val onCode: (String) -> Unit,
    private val onError: (String) -> Unit,
    readTimeoutMillis: Int = LocalHttpServer.READ_TIMEOUT_MS,
    deadlineMillis: Long = LocalHttpServer.DEADLINE_MS,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : LocalHttpServer.Handler {
    private val server = LocalHttpServer("google-consent", MAX_BODY_BYTES, this, readTimeoutMillis, deadlineMillis, nowMillis)
    private val delivered = AtomicBoolean(false)

    @Volatile
    private var googleUrl = ""

    @Volatile
    var pageUrl = ""
        private set

    @Volatile
    var pickerUrl: String? = null

    /** Avvia la pagina su [address] e restituisce il suo indirizzo, da mettere nel QR. */
    fun start(address: InetAddress): String {
        val port = server.start(address)
        val host = GuestUploadServer.hostHeader(address, port)
        pageUrl = "http://$host/?k=$key"
        googleUrl = authorizationUrl("http://$host/google?k=$key")
        return pageUrl
    }

    fun stop() = server.stop()

    fun close() = server.close()

    override fun handle(request: GuestUploadServer.Request, body: LocalHttpServer.Body): LocalHttpServer.Response {
        if (request.method != "GET") return expired(405)
        if (!keyMatches(request.query["k"])) return expired(403)
        return when (request.path) {
            "/" -> LocalHttpServer.Response.html(200, page(), LocalHttpServer.SCRIPT_PAGE_POLICY)
            "/start" -> LocalHttpServer.Response.json(200, """{"url":${GuestUploadServer.jsonString(googleUrl)}}""")
            "/status" -> LocalHttpServer.Response.json(200, pickerUrl?.let { """{"picker":${GuestUploadServer.jsonString(it)}}""" } ?: "{}")
            "/google" -> {
                val code = request.query["code"]?.let(::decode)
                val error = request.query["error"]?.let(::decode)
                when {
                    !code.isNullOrBlank() -> {
                        // Un ritorno ripetuto (ricarica della pagina) non consegna due volte lo stesso codice.
                        if (delivered.compareAndSet(false, true)) onCode(code)
                        LocalHttpServer.Response.redirect("/?k=$key#done")
                    }
                    !error.isNullOrBlank() -> {
                        if (delivered.compareAndSet(false, true)) onError(error)
                        LocalHttpServer.Response.redirect("/?k=$key#cancelled")
                    }
                    else -> expired(400)
                }
            }
            else -> expired(404)
        }
    }

    override fun error(status: Int, acceptLanguage: String?): LocalHttpServer.Response = expired(status)

    private fun expired(status: Int) = LocalHttpServer.Response.html(status, EXPIRED_PAGE)

    private fun keyMatches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), key.toByteArray())

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    companion object {
        private const val MAX_BODY_BYTES = 1_024

        private const val EXPIRED_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>FotoFesta</title></head>
<body style="font:18px/1.5 system-ui,sans-serif;margin:32px 20px;background:#0b1222;color:#eef2fa">
<h1 style="font-size:1.4rem">FotoFesta</h1>
<p>Questo link non è più valido: inquadra di nuovo il QR sulla TV.</p>
<p>This link is no longer valid: scan the QR code on the TV again.</p>
</body></html>"""
    }
}
