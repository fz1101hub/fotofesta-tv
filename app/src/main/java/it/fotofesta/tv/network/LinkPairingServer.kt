package it.fotofesta.tv.network

import it.fotofesta.tv.guests.GuestUploadServer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
 * scrive con il telecomando. Vuole la chiave del QR, controlla l'intestazione Host e accetta solo
 * moduli piccoli.
 */
class LinkPairingServer(
    private val key: String,
    private val texts: (acceptLanguage: String?) -> PairingTexts,
    /** Chiamato con il testo incollato; true se è un link valido. */
    private val onLink: (String) -> Boolean,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var expectedHost: String? = null
    private val workers = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "link-pairing").apply { isDaemon = true } }

    /** Avvia la pagina su [address] e restituisce la porta. */
    @Synchronized
    fun start(address: InetAddress, port: Int = 0): Int {
        stop()
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(address, port), 8)
        } catch (failure: IOException) {
            socket.close()
            throw failure
        }
        serverSocket = socket
        expectedHost = GuestUploadServer.hostHeader(address, socket.localPort)
        thread(name = "link-pairing-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: IOException) {
                    continue
                }
                runCatching { workers.execute { serve(client) } }.onFailure { runCatching { client.close() } }
            }
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** Ferma anche i thread: dopo non si può più riavviare. */
    fun close() {
        stop()
        workers.shutdownNow()
        workers.awaitTermination(1, TimeUnit.SECONDS)
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val response = try {
                val deadline = System.currentTimeMillis() + DEADLINE_MS
                val request = GuestUploadServer.readRequest(input, deadline) { System.currentTimeMillis() } ?: return
                route(request, input)
            } catch (_: IOException) {
                return
            } catch (_: Exception) {
                // Richiesta malformata (riga, intestazioni o modulo).
                Page(400, page(texts(null), message = null, form = false, status = null))
            }
            runCatching { write(output, response) }
        }
    }

    private fun route(request: GuestUploadServer.Request, input: InputStream): Page {
        val language = request.headers["accept-language"]
        val text = texts(language)
        // Contro il DNS rebinding: la pagina risponde solo all'indirizzo del QR.
        if (request.headers["host"] != expectedHost) return Page(421, page(text, text.expired, form = false))
        return when {
            request.path == "/" && request.method == "GET" ->
                if (keyMatches(request.query["k"])) Page(200, page(text, null, form = true)) else Page(403, page(text, text.expired, form = false))
            request.path == "/link" && request.method == "POST" -> {
                val length = request.headers["content-length"]?.trim()?.toIntOrNull()
                if (request.headers.containsKey("transfer-encoding") || length == null) return Page(411, page(text, text.invalid, form = true))
                if (length > MAX_FORM_BYTES) return Page(413, page(text, text.invalid, form = true))
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(body, read, length - read)
                    if (count < 0) throw IOException("Modulo incompleto")
                    read += count
                }
                val form = GuestUploadServer.parseForm(String(body, Charsets.UTF_8))
                when {
                    !keyMatches(form["k"]) -> Page(403, page(text, text.expired, form = false))
                    onLink(form["link"].orEmpty()) -> Page(200, page(text, text.received, form = false, status = "ok"))
                    else -> Page(400, page(text, text.invalid, form = true, status = "error"))
                }
            }
            else -> Page(404, page(text, text.expired, form = false))
        }
    }

    private fun keyMatches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), key.toByteArray())

    private class Page(val status: Int, val html: String)

    private fun page(text: PairingTexts, message: String?, form: Boolean, status: String? = null): String = buildString {
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
            append("<p class=\"").append(if (status == "ok") "ok" else if (status == "error") "error" else "").append("\">")
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

    private fun write(output: OutputStream, page: Page) {
        val body = page.html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(page.status).append(" X\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    companion object {
        private const val READ_TIMEOUT_MS = 10_000
        private const val DEADLINE_MS = 20_000L
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
