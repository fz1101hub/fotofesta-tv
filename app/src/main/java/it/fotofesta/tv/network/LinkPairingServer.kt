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
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
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
 * moduli piccoli; poche connessioni per volta e per telefono, ognuna con una scadenza complessiva.
 * Dopo [stop] nessun link viene più consegnato a [onLink].
 */
class LinkPairingServer(
    private val key: String,
    private val texts: (acceptLanguage: String?) -> PairingTexts,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MS,
    private val deadlineMillis: Long = DEADLINE_MS,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Chiamato con il testo incollato; true se è un link valido. */
    private val onLink: (String) -> Boolean,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var expectedHost: String? = null

    /** Dopo stop(): le richieste ancora in corso rispondono "scaduto" e non consegnano link. */
    @Volatile
    private var closed = true
    private val openSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    private val perClient = mutableMapOf<InetAddress, Int>()
    private val workers = ThreadPoolExecutor(
        WORKERS, WORKERS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE_SIZE),
    ) { runnable -> Thread(runnable, "link-pairing").apply { isDaemon = true } }

    /** Avvia la pagina su [address] e restituisce la porta. */
    @Synchronized
    fun start(address: InetAddress, port: Int = 0): Int {
        stop()
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(address, port), BACKLOG)
        } catch (failure: IOException) {
            socket.close()
            throw failure
        }
        serverSocket = socket
        expectedHost = GuestUploadServer.hostHeader(address, socket.localPort)
        closed = false
        thread(name = "link-pairing-accept", isDaemon = true) { acceptLoop(socket) }
        return socket.localPort
    }

    /** Chiude la porta e le connessioni aperte: da qui in poi [onLink] non viene più chiamato. */
    @Synchronized
    fun stop() {
        closed = true
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(openSockets) {
            openSockets.forEach { runCatching { it.close() } }
            openSockets.clear()
        }
    }

    /** Ferma anche i thread: dopo non si può più riavviare. */
    fun close() {
        stop()
        workers.shutdownNow()
        workers.awaitTermination(1, TimeUnit.SECONDS)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                continue
            }
            val clientAddress = client.inetAddress
            if (!acquireClient(clientAddress)) {
                reject(client, 429)
                continue
            }
            openSockets += client
            try {
                workers.execute { serve(client, clientAddress) }
            } catch (_: RejectedExecutionException) {
                openSockets -= client
                releaseClient(clientAddress)
                reject(client, 503)
            }
        }
    }

    /** Risposta breve dal thread di ascolto, senza leggere la richiesta. */
    private fun reject(client: Socket, status: Int) {
        runCatching {
            client.use { socket ->
                socket.soTimeout = 1_000
                write(socket.getOutputStream(), Page(status, page(texts(null), message = null, form = false, status = null)), retryAfter = true)
            }
        }
    }

    private fun serve(client: Socket, clientAddress: InetAddress) {
        try {
            client.use { socket ->
                socket.soTimeout = readTimeoutMillis
                val deadline = nowMillis() + deadlineMillis
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val response = try {
                    val request = GuestUploadServer.readRequest(input, deadline, nowMillis) ?: return
                    route(request, input, deadline)
                } catch (_: IOException) {
                    return
                } catch (_: Exception) {
                    // Richiesta malformata (riga, intestazioni o modulo).
                    Page(400, page(texts(null), message = null, form = false, status = null))
                }
                runCatching {
                    write(output, response)
                    // Chiudere con dati ancora da leggere (un modulo rifiutato per dimensione) farebbe
                    // arrivare un reset al posto della risposta: si chiude l'invio e si scarta il resto.
                    socket.shutdownOutput()
                    socket.soTimeout = DRAIN_TIMEOUT_MS
                    drain(input)
                }
            }
        } catch (_: Throwable) {
            // Connessione caduta o errore inatteso: non c'è nessuno a cui rispondere.
        } finally {
            openSockets -= client
            releaseClient(clientAddress)
        }
    }

    private fun route(request: GuestUploadServer.Request, input: InputStream, deadline: Long): Page {
        val language = request.headers["accept-language"]
        val text = texts(language)
        // Contro il DNS rebinding: la pagina risponde solo all'indirizzo del QR.
        if (!request.headers["host"].equals(expectedHost, ignoreCase = true)) return Page(421, page(text, text.expired, form = false))
        return when {
            request.path == "/" && request.method == "GET" ->
                if (keyMatches(request.query["k"]) && !closed) Page(200, page(text, null, form = true)) else Page(403, page(text, text.expired, form = false))
            request.path == "/link" && request.method == "POST" -> {
                val length = request.headers["content-length"]?.trim()?.toIntOrNull()
                // Le pagine di errore non ripetono il modulo: contengono la chiave del QR e qui non è stata provata.
                if (request.headers.containsKey("transfer-encoding") || length == null) return Page(411, page(text, text.invalid, form = false))
                if (length > MAX_FORM_BYTES) return Page(413, page(text, text.invalid, form = false))
                val form = GuestUploadServer.parseForm(String(readBody(input, length, deadline), Charsets.UTF_8))
                when {
                    !keyMatches(form["k"]) || closed -> Page(403, page(text, text.expired, form = false))
                    onLink(form["link"].orEmpty()) -> Page(200, page(text, text.received, form = false, status = "ok"))
                    else -> Page(400, page(text, text.invalid, form = true, status = "error"))
                }
            }
            else -> Page(404, page(text, text.expired, form = false))
        }
    }

    private fun readBody(input: InputStream, length: Int, deadline: Long): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) throw IOException("Modulo incompleto")
            if (nowMillis() > deadline) throw SocketTimeoutException("Modulo troppo lento")
            read += count
        }
        return body
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(8 * 1024)
        var total = 0
        try {
            while (total < MAX_DRAIN_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
        } catch (_: IOException) {
        }
    }

    private fun acquireClient(address: InetAddress): Boolean = synchronized(perClient) {
        val count = perClient[address] ?: 0
        if (count >= MAX_CONNECTIONS_PER_CLIENT) return false
        perClient[address] = count + 1
        true
    }

    private fun releaseClient(address: InetAddress) = synchronized(perClient) {
        val count = (perClient[address] ?: 1) - 1
        if (count <= 0) perClient.remove(address) else perClient[address] = count
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

    private fun write(output: OutputStream, page: Page, retryAfter: Boolean = false) {
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
            if (retryAfter) append("Retry-After: 5\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    companion object {
        const val READ_TIMEOUT_MS = 10_000
        const val DEADLINE_MS = 20_000L
        private const val MAX_FORM_BYTES = 4_096
        private const val MAX_DRAIN_BYTES = 64 * 1024
        private const val DRAIN_TIMEOUT_MS = 1_000
        /** Un telefono apre una connessione per la pagina e una per il modulo, più qualcuna "di scorta". */
        internal const val MAX_CONNECTIONS_PER_CLIENT = 3
        private const val WORKERS = 3
        private const val QUEUE_SIZE = 6
        private const val BACKLOG = 8

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
