package it.fotofesta.tv.guests

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Piccolo server HTTP sulla rete di casa: dà agli invitati la pagina per mandare le foto e le
 * riceve. Le foto non passano da internet: vanno dal telefono alla TV attraverso il router.
 *
 * Solo chi ha inquadrato il QR conosce [key], richiesta sia per aprire la pagina sia per inviare.
 * Ogni richiesta usa una connessione propria, con limiti su intestazioni, dimensione e tempi totali
 * (non solo per lettura: una connessione lentissima non tiene occupato il server), un massimo di
 * connessioni e di invii al minuto per telefono e un tetto ai byte in arrivo contemporaneamente.
 * Se il server si ferma da solo (ad esempio quando la rete cade) viene chiamato [onFailure].
 */
class GuestUploadServer(
    private val key: String,
    private val page: () -> String,
    private val onPhoto: (bytes: ByteArray, sender: String?) -> UploadResult,
    private val onFailure: () -> Unit = {},
    private val maxUploadBytes: Int = MAX_UPLOAD_BYTES,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MS,
    private val headerDeadlineMillis: Long = HEADER_DEADLINE_MS,
    private val bodyDeadlineMillis: Long = BODY_DEADLINE_MS,
    private val maxUploadsPerMinute: Int = MAX_UPLOADS_PER_MINUTE,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class UploadResult { SHOWN, PENDING, FULL, TOO_MANY_PENDING, UNSUPPORTED }

    private var serverSocket: ServerSocket? = null
    private var executor: ThreadPoolExecutor? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var stopping = false
    private val openSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    @Volatile private var expectedHost: String = ""
    private val perClient = mutableMapOf<InetAddress, Int>()
    private val recentUploads = mutableMapOf<InetAddress, ArrayDeque<Long>>()
    private val inFlight = Semaphore(MAX_IN_FLIGHT_BYTES)
    // Invii già ricevuti, per non salvare due volte una foto se il telefono ripete l'invio.
    private val handledUploads = object : LinkedHashMap<String, Response>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Response>?) = size > 200
    }

    /** Il server accetta connessioni: socket aperto e thread di ascolto vivo. */
    val isRunning: Boolean
        @Synchronized get() = serverSocket?.isClosed == false && acceptThread?.isAlive == true

    /**
     * Avvia il server su [address], preferibilmente sulla porta [preferredPort] (così il QR resta
     * uguale tra un avvio e l'altro), altrimenti su una porta libera. Restituisce la porta usata.
     */
    @Synchronized
    @Throws(IOException::class)
    fun start(address: InetAddress, preferredPort: Int): Int {
        stop()
        stopping = false
        val socket = bind(address, preferredPort)
        val port = socket.localPort
        expectedHost = hostHeader(address, port)
        val counter = AtomicInteger()
        val pool = ThreadPoolExecutor(
            WORKERS, WORKERS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE_SIZE),
        ) { runnable ->
            Thread(runnable, "guest-upload-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        serverSocket = socket
        executor = pool
        acceptThread = Thread({ acceptLoop(socket, pool) }, "guest-upload-accept").apply {
            isDaemon = true
            start()
        }
        return port
    }

    /**
     * Ferma il server e aspetta (al massimo due secondi) che le richieste in corso finiscano: dopo
     * il ritorno nessuna foto viene più consegnata a [onPhoto].
     */
    fun stop() {
        val pool: ThreadPoolExecutor?
        synchronized(this) {
            stopping = true
            runCatching { serverSocket?.close() }
            serverSocket = null
            pool = executor
            executor = null
            pool?.shutdownNow()
            // Le letture dei socket non si interrompono con interrupt(): si chiudono direttamente.
            synchronized(openSockets) {
                openSockets.forEach { runCatching { it.close() } }
                openSockets.clear()
            }
        }
        pool?.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun bind(address: InetAddress, preferredPort: Int): ServerSocket {
        for (port in listOf(preferredPort, 0)) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(address, port), BACKLOG)
                return socket
            } catch (failure: IOException) {
                runCatching { socket.close() }
                if (port == 0) throw failure
            }
        }
        error("Nessuna porta disponibile")
    }

    private fun acceptLoop(server: ServerSocket, pool: ThreadPoolExecutor) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                // Chiusura voluta da stop(), oppure il sistema ha tolto l'indirizzo: in quel caso
                // chi ha avviato il server deve riavviarlo.
                runCatching { server.close() }
                if (!stopping) onFailure()
                break
            }
            val clientAddress = client.inetAddress
            if (!acquireClient(clientAddress)) {
                // Troppe connessioni dallo stesso telefono.
                reject(client, 429)
                continue
            }
            try {
                openSockets += client
                pool.execute { serve(client, clientAddress) }
            } catch (_: RejectedExecutionException) {
                // Troppe richieste insieme: il telefono riproverà dopo qualche secondo.
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
                val output = BufferedOutputStream(socket.getOutputStream())
                write(output, Response.json(status, """{"error":"busy"}""", listOf("Retry-After" to "5")))
                output.flush()
            }
        }
    }

    private fun serve(client: Socket, clientAddress: InetAddress) {
        try {
            client.use { socket ->
                socket.soTimeout = readTimeoutMillis
                val startedAt = nowMillis()
                val input = BufferedInputStream(socket.getInputStream())
                val response = try {
                    val request = readRequest(input, deadline = startedAt + headerDeadlineMillis, now = nowMillis) ?: return
                    route(request, input, clientAddress, startedAt)
                } catch (error: HttpError) {
                    Response.json(error.status, """{"error":"${error.code}"}""", error.headers)
                }
                val output = BufferedOutputStream(socket.getOutputStream())
                write(output, response)
                output.flush()
                // Chiudere con dati ancora da leggere farebbe arrivare un reset al posto della risposta
                // (ad esempio dopo un rifiuto per dimensione): si chiude prima l'invio e si scarta il resto.
                socket.shutdownOutput()
                socket.soTimeout = DRAIN_TIMEOUT_MS
                drain(input)
            }
        } catch (_: IOException) {
            // Connessione caduta o troppo lenta: non c'è nessuno a cui rispondere.
        } catch (_: Throwable) {
            // Anche un errore inatteso (ad esempio memoria esaurita) non deve chiudere l'app.
        } finally {
            openSockets -= client
            releaseClient(clientAddress)
        }
    }

    private fun route(request: Request, body: InputStream, clientAddress: InetAddress, startedAt: Long): Response {
        val host = request.headers["host"]
        if (host != null && !host.equals(expectedHost, ignoreCase = true)) {
            throw HttpError(421, "host")
        }
        return when (request.path) {
            "/" -> {
                if (request.method != "GET" && request.method != "HEAD") throw HttpError(405, "method")
                if (!keyMatches(request.query["k"])) Response.html(403, EXPIRED_PAGE)
                else Response.html(200, page(), head = request.method == "HEAD")
            }
            "/photos" -> {
                if (request.method != "POST") throw HttpError(405, "method")
                receivePhoto(request, body, clientAddress, startedAt)
            }
            else -> Response.json(404, """{"error":"not_found"}""")
        }
    }

    private fun receivePhoto(request: Request, body: InputStream, clientAddress: InetAddress, startedAt: Long): Response {
        if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
        if (request.headers.containsKey("transfer-encoding")) throw HttpError(411, "length")
        val length = request.headers["content-length"]?.trim()?.toLongOrNull() ?: throw HttpError(411, "length")
        if (length <= 0) throw HttpError(400, "empty")
        if (length > maxUploadBytes) throw HttpError(413, "too_large")
        val uploadId = request.headers["x-fotofesta-upload"]?.takeIf { it.length in 8..64 && it.all(::isIdChar) }
        uploadId?.let { id -> synchronized(handledUploads) { handledUploads[id] }?.let { return it } }
        if (!allowUpload(clientAddress)) throw HttpError(429, "rate", listOf("Retry-After" to "10"))
        val size = length.toInt()
        if (!inFlight.tryAcquire(size, IN_FLIGHT_WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw HttpError(503, "busy", listOf("Retry-After" to "5"))
        }
        val response = try {
            val bytes = readBody(body, size, deadline = startedAt + bodyDeadlineMillis, now = nowMillis)
            if (ImageFormat.sniff(bytes) == null) throw HttpError(415, "unsupported")
            val sender = request.headers["x-fotofesta-sender"]?.let(::decodeSender)
            when (onPhoto(bytes, sender)) {
                UploadResult.SHOWN -> Response.json(200, """{"status":"shown"}""")
                UploadResult.PENDING -> Response.json(200, """{"status":"pending"}""")
                UploadResult.FULL -> throw HttpError(507, "full")
                UploadResult.TOO_MANY_PENDING -> throw HttpError(507, "pending_full")
                UploadResult.UNSUPPORTED -> throw HttpError(415, "unsupported")
            }
        } finally {
            inFlight.release(size)
        }
        uploadId?.let { id -> synchronized(handledUploads) { handledUploads[id] = response } }
        return response
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

    /** Al massimo [maxUploadsPerMinute] invii al minuto dallo stesso telefono. */
    private fun allowUpload(address: InetAddress): Boolean = synchronized(recentUploads) {
        val now = nowMillis()
        val times = recentUploads.getOrPut(address) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.peekFirst()!! > 60_000) times.pollFirst()
        if (times.size >= maxUploadsPerMinute) return false
        times.addLast(now)
        true
    }

    private fun keyMatches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), key.toByteArray())

    private fun decodeSender(value: String): String? =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()?.let(GuestSender::clean)

    private fun isIdChar(char: Char) = char.isLetterOrDigit() || char == '-' || char == '_'

    private class HttpError(
        val status: Int,
        val code: String,
        val headers: List<Pair<String, String>> = emptyList(),
    ) : Exception(code)

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
    )

    private class Response(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val extraHeaders: List<Pair<String, String>> = emptyList(),
        val head: Boolean = false,
    ) {
        companion object {
            fun json(status: Int, body: String, headers: List<Pair<String, String>> = emptyList()) =
                Response(status, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8), headers)

            fun html(status: Int, body: String, head: Boolean = false) = Response(
                status,
                "text/html; charset=utf-8",
                body.toByteArray(Charsets.UTF_8),
                listOf("Content-Security-Policy" to PAGE_POLICY),
                head,
            )
        }
    }

    private fun write(output: OutputStream, response: Response) {
        val headers = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            response.extraHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        if (!response.head) output.write(response.body)
    }

    companion object {
        /** Più di quanto serve a una foto 4K già ridotta dal telefono. */
        const val MAX_UPLOAD_BYTES = 12 * 1024 * 1024
        const val READ_TIMEOUT_MS = 10_000
        const val HEADER_DEADLINE_MS = 15_000L
        const val BODY_DEADLINE_MS = 120_000L
        const val MAX_UPLOADS_PER_MINUTE = 60
        private const val MAX_CONNECTIONS_PER_CLIENT = 3
        /** Byte di foto in arrivo contemporaneamente, per tutti i telefoni insieme. */
        private const val MAX_IN_FLIGHT_BYTES = 24 * 1024 * 1024
        private const val IN_FLIGHT_WAIT_SECONDS = 10L
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_DRAIN_BYTES = 256 * 1024
        private const val DRAIN_TIMEOUT_MS = 1_000
        private const val WORKERS = 6
        private const val QUEUE_SIZE = 16
        private const val BACKLOG = 32

        private const val PAGE_POLICY = "default-src 'none'; script-src 'unsafe-inline'; " +
            "style-src 'unsafe-inline'; img-src blob: data:; connect-src 'self'; " +
            "form-action 'none'; base-uri 'none'; frame-ancestors 'none'"

        private const val EXPIRED_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>FotoFesta</title></head>
<body style="font:18px/1.5 system-ui,sans-serif;margin:32px 20px;background:#0b1222;color:#eef2fa">
<h1 style="font-size:1.4rem">FotoFesta</h1>
<p>Questo link non è più valido: inquadra di nuovo il QR sulla TV.</p>
<p>This link is no longer valid: scan the QR code on the TV again.</p>
</body></html>"""

        internal fun hostHeader(address: InetAddress, port: Int): String {
            val host = address.hostAddress.orEmpty()
            return if (':' in host) "[$host]:$port" else "$host:$port"
        }

        /**
         * Legge riga di richiesta e intestazioni; null se il client chiude senza mandare nulla.
         * Oltre [deadline] (in base a [now]) la richiesta viene abbandonata anche se i byte arrivano.
         */
        internal fun readRequest(
            input: InputStream,
            deadline: Long = Long.MAX_VALUE,
            now: () -> Long = { 0L },
        ): Request? {
            val head = readHead(input, deadline, now) ?: return null
            val lines = head.split("\r\n", "\n")
            val parts = lines.first().split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "request")
            val target = parts[1]
            if (!target.startsWith("/")) throw HttpError(400, "request")
            val headers = mutableMapOf<String, String>()
            lines.drop(1).filter(String::isNotEmpty).forEach { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) throw HttpError(400, "header")
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                // Due Content-Length diversi renderebbero ambigua la fine del corpo.
                if (name == "content-length" && headers[name]?.let { it != value } == true) throw HttpError(400, "header")
                headers[name] = value
            }
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter(String::isNotEmpty).associate { pair ->
                pair.substringBefore('=') to pair.substringAfter('=', "")
            }
            return Request(parts[0], path, query, headers)
        }

        private fun readHead(input: InputStream, deadline: Long, now: () -> Long): String? {
            val buffer = java.io.ByteArrayOutputStream()
            var previous = -1
            var beforePrevious = -1
            var third = -1
            while (true) {
                val byte = input.read()
                if (byte == -1) {
                    if (buffer.size() == 0) return null
                    throw EOFException("Intestazioni incomplete")
                }
                if (now() > deadline) throw SocketTimeoutException("Intestazioni troppo lente")
                if (buffer.size() >= MAX_HEADER_BYTES) throw HttpError(431, "header")
                buffer.write(byte)
                val endsWithCrlfCrlf = byte == '\n'.code && previous == '\r'.code &&
                    beforePrevious == '\n'.code && third == '\r'.code
                val endsWithLfLf = byte == '\n'.code && previous == '\n'.code
                if (endsWithCrlfCrlf || endsWithLfLf) break
                third = beforePrevious
                beforePrevious = previous
                previous = byte
            }
            return buffer.toString(Charsets.ISO_8859_1.name()).trimEnd()
        }

        private fun drain(input: InputStream) {
            val buffer = ByteArray(8 * 1024)
            var total = 0
            try {
                while (total < MAX_DRAIN_BYTES) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                }
            } catch (_: IOException) {
            }
        }

        private fun readBody(input: InputStream, length: Int, deadline: Long, now: () -> Long): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read == -1) throw EOFException("Corpo incompleto")
                if (now() > deadline) throw SocketTimeoutException("Invio troppo lento")
                offset += read
            }
            return bytes
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            421 -> "Misdirected Request"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            507 -> "Insufficient Storage"
            else -> "Error"
        }
    }
}

/** Formati accettati, riconosciuti dai primi byte e non dall'intestazione mandata dal telefono. */
enum class ImageFormat {
    JPEG, PNG, WEBP;

    companion object {
        fun sniff(bytes: ByteArray): ImageFormat? = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> JPEG
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> PNG
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> WEBP
            else -> null
        }

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

/** Nome facoltativo con cui l'invitato firma le foto, ripulito prima di mostrarlo sulla TV. */
object GuestSender {
    const val MAX_LENGTH = 40

    fun clean(value: String?): String? {
        if (value == null) return null
        val result = StringBuilder()
        var count = 0
        var pendingSpace = false
        var index = 0
        while (index < value.length && count < MAX_LENGTH) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> pendingSpace = result.isNotEmpty()
                isHidden(codePoint) -> Unit
                else -> {
                    if (pendingSpace) {
                        result.append(' ')
                        count++
                        pendingSpace = false
                        if (count >= MAX_LENGTH) break
                    }
                    result.appendCodePoint(codePoint)
                    count++
                }
            }
        }
        return result.toString().takeIf(String::isNotEmpty)
    }

    /** Caratteri di controllo, invisibili o che cambiano la direzione del testo. */
    private fun isHidden(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true
        else -> false
    }
}
