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
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Piccolo server HTTP per le pagine che la TV serve ai telefoni sulla rete di casa (link di un
 * album iCloud, autorizzazione di Google Foto): una richiesta per connessione, poche connessioni
 * per volta e per telefono, scadenze complessive, corpi piccoli e controllo dell'intestazione Host
 * contro il DNS rebinding. Dopo [stop] le connessioni aperte si chiudono e le richieste ancora in
 * corso ricevono la risposta "scaduta" di [Handler.error], senza arrivare alla logica della pagina.
 */
class LocalHttpServer(
    private val threadName: String,
    private val maxBodyBytes: Int,
    private val handler: Handler,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MS,
    private val deadlineMillis: Long = DEADLINE_MS,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    sealed interface Body {
        class Bytes(val bytes: ByteArray) : Body

        /** Senza Content-Length, o con Transfer-Encoding: il corpo non è stato letto. */
        data object Missing : Body

        /** Content-Length oltre il limite: il corpo non è stato letto. */
        data object TooLarge : Body
    }

    class Response(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: List<Pair<String, String>> = emptyList(),
    ) {
        companion object {
            fun html(status: Int, html: String, policy: String = PAGE_POLICY, headers: List<Pair<String, String>> = emptyList()) =
                Response(status, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8), listOf("Content-Security-Policy" to policy) + headers)

            fun json(status: Int, json: String) =
                Response(status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))

            /** Rimanda il browser a [location] (303: anche dopo un POST si passa a un GET). */
            fun redirect(location: String) =
                Response(303, "text/plain; charset=utf-8", ByteArray(0), listOf("Location" to location))
        }
    }

    interface Handler {
        /** Risposta a una richiesta con la chiave e l'Host giusti, letta per intero. */
        fun handle(request: GuestUploadServer.Request, body: Body): Response

        /**
         * Risposte decise dal server: 400 (richiesta malformata), 403 (dopo [stop]), 421 (Host
         * sbagliato), 429 e 503 (troppe connessioni per telefono o in tutto).
         */
        fun error(status: Int, acceptLanguage: String?): Response
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var expectedHost: String? = null

    /** Dopo stop(): le richieste ancora in corso rispondono "scaduto". */
    @Volatile
    var isClosed = true
        private set
    private val openSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    private val perClient = mutableMapOf<InetAddress, Int>()
    private val workers = ThreadPoolExecutor(
        WORKERS, WORKERS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE_SIZE),
    ) { runnable -> Thread(runnable, threadName).apply { isDaemon = true } }

    /** Avvia il server su [address] e restituisce la porta. */
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
        isClosed = false
        thread(name = "$threadName-accept", isDaemon = true) { acceptLoop(socket) }
        return socket.localPort
    }

    /** Chiude la porta e le connessioni aperte: da qui in poi [handler] non riceve più richieste. */
    @Synchronized
    fun stop() {
        isClosed = true
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
                write(socket.getOutputStream(), handler.error(status, null), retryAfter = true)
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
                    // Richiesta malformata (riga, intestazioni o corpo).
                    handler.error(400, null)
                }
                runCatching {
                    write(output, response)
                    // Chiudere con dati ancora da leggere (un corpo rifiutato per dimensione) farebbe
                    // arrivare un reset al posto della risposta: si chiude l'invio e si scarta il resto.
                    socket.shutdownOutput()
                    socket.soTimeout = DRAIN_TIMEOUT_MS
                    drain(input, nowMillis() + DRAIN_TIMEOUT_MS)
                }
            }
        } catch (_: Throwable) {
            // Connessione caduta o errore inatteso: non c'è nessuno a cui rispondere.
        } finally {
            openSockets -= client
            releaseClient(clientAddress)
        }
    }

    private fun route(request: GuestUploadServer.Request, input: InputStream, deadline: Long): Response {
        val language = request.headers["accept-language"]
        // Contro il DNS rebinding: la pagina risponde solo all'indirizzo del QR.
        if (!request.headers["host"].equals(expectedHost, ignoreCase = true)) return handler.error(421, language)
        val body = if (request.method == "POST") {
            val length = request.headers["content-length"]?.trim()?.toIntOrNull()
            when {
                request.headers.containsKey("transfer-encoding") || length == null -> Body.Missing
                length > maxBodyBytes -> Body.TooLarge
                else -> Body.Bytes(readBody(input, length, deadline))
            }
        } else {
            Body.Bytes(ByteArray(0))
        }
        if (isClosed) return handler.error(403, language)
        return handler.handle(request, body)
    }

    private fun readBody(input: InputStream, length: Int, deadline: Long): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) throw IOException("Corpo incompleto")
            if (nowMillis() > deadline) throw SocketTimeoutException("Corpo troppo lento")
            read += count
        }
        return body
    }

    /** Scarta al massimo [MAX_DRAIN_BYTES] e per al massimo un secondo in tutto: un client che manda un byte alla volta non tiene occupato il worker. */
    private fun drain(input: InputStream, deadline: Long) {
        val buffer = ByteArray(8 * 1024)
        var total = 0
        try {
            while (total < MAX_DRAIN_BYTES && nowMillis() < deadline) {
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

    private fun write(output: OutputStream, response: Response, retryAfter: Boolean = false) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            response.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            if (retryAfter) append("Retry-After: 5\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }

    companion object {
        const val READ_TIMEOUT_MS = 10_000
        const val DEADLINE_MS = 20_000L
        private const val MAX_DRAIN_BYTES = 64 * 1024
        private const val DRAIN_TIMEOUT_MS = 1_000
        /** Un telefono apre una connessione per la pagina e una per il modulo, più qualcuna "di scorta". */
        const val MAX_CONNECTIONS_PER_CLIENT = 3
        private const val WORKERS = 3
        private const val QUEUE_SIZE = 6
        private const val BACKLOG = 8

        /** Pagine senza script: solo stile in linea e moduli verso la TV. */
        const val PAGE_POLICY = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"

        /** Pagine con script in linea che parlano solo con la TV. */
        const val SCRIPT_PAGE_POLICY = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'"

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            303 -> "See Other"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            421 -> "Misdirected Request"
            429 -> "Too Many Requests"
            503 -> "Service Unavailable"
            else -> "Error"
        }
    }
}
