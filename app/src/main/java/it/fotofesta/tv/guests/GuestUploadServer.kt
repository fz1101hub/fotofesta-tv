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
    /** [player] è l'identificativo casuale del telefono, per il limite di foto a testa. */
    private val onPhoto: (bytes: ByteArray, sender: String?, player: String?) -> Upload,
    /**
     * Video breve di un invitato: [input] dà esattamente [length] byte, da copiare su disco senza
     * tenerli in RAM. Null se la TV non accetta video.
     */
    private val onVideo: ((input: InputStream, length: Long, sender: String?, player: String?) -> Upload)? = null,
    /** Foto tolta da chi l'ha mandata, con la ricevuta avuta all'invio; false se non c'è più. */
    private val onDeletePhoto: (id: String) -> Boolean = { false },
    /**
     * Codice di quattro cifre mostrato sulla TV: chi apre "festa.local" senza QR lo scrive e riceve
     * la chiave della festa. Null se non c'è.
     */
    private val joinCode: () -> String? = { null },
    private val onFailure: () -> Unit = {},
    /** Gioco con i telefoni: domanda in corso (GET /game) e risposte (POST /game/answer). */
    private val game: PhoneGame? = null,
    /** Reazioni e dediche (GET /party con lo stato per la pagina, POST /party/action). */
    private val hub: PartyHub? = null,
    /** Regia dal telefono con il PIN (/party/host...). */
    private val hostRemote: HostRemote? = null,
    /** Foto in attesa di approvazione, per le miniature della pagina di regia. */
    private val hostPhoto: (String) -> ByteArray? = { null },
    /** Giochi con i telefoni oltre ai quiz: disegni, frasi, missioni, caccia al QR, luci. */
    private val play: PartyPlay? = null,
    /** QR dell'ingresso e parete delle foto per un secondo schermo. */
    private val extras: PartyExtras? = null,
    private val maxUploadBytes: Int = MAX_UPLOAD_BYTES,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MS,
    private val headerDeadlineMillis: Long = HEADER_DEADLINE_MS,
    private val bodyDeadlineMillis: Long = BODY_DEADLINE_MS,
    private val maxUploadsPerMinute: Int = MAX_UPLOADS_PER_MINUTE,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class UploadResult { SHOWN, PENDING, FULL, TOO_MANY_PENDING, UNSUPPORTED, LIMIT, QUIET }

    /** Esito di un invio, con l'identificativo della foto salvata (per la ricevuta di chi l'ha mandata). */
    data class Upload(val result: UploadResult, val photoId: String? = null)

    private var serverSocket: ServerSocket? = null
    private var executor: ThreadPoolExecutor? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var stopping = false
    private val openSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    @Volatile private var expectedHost: String = ""
    @Volatile private var localName: String = ""
    private val joinAttempts = mutableMapOf<InetAddress, ArrayDeque<Long>>()
    private var joinFailures = 0
    private var joinLockedUntil = 0L

    /** Ultima volta (orologio di [nowMillis]) che un telefono ha letto lo stato della festa: per la prova della rete. */
    @Volatile var lastGuestContact = 0L
        private set
    private val perClient = mutableMapOf<InetAddress, Int>()
    private val recentUploads = mutableMapOf<InetAddress, ArrayDeque<Long>>()
    private val recentActions = mutableMapOf<InetAddress, ArrayDeque<Long>>()
    private val inFlight = Semaphore(MAX_IN_FLIGHT_BYTES)
    private val videoSlots = Semaphore(MAX_VIDEO_UPLOADS)
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
        localName = "$LOCAL_NAME:$port"
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
                drain(input, deadline = nowMillis() + DRAIN_TIMEOUT_MS, now = nowMillis)
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
        // Contro il DNS rebinding: solo l'indirizzo del QR; un client senza Host non è un browser.
        val host = request.headers["host"]
        // "festa.local" è un nome della rete locale (mDNS), che un sito esterno non può ottenere.
        if (host == null || !(host.equals(expectedHost, ignoreCase = true) || host.equals(localName, ignoreCase = true))) {
            throw HttpError(421, "host")
        }
        return when (request.path) {
            "/" -> {
                if (request.method != "GET" && request.method != "HEAD") throw HttpError(405, "method")
                when {
                    keyMatches(request.query["k"]) -> Response.html(200, page(), head = request.method == "HEAD")
                    // Senza chiave (indirizzo scritto a mano): si chiede il codice mostrato sulla TV.
                    request.query["k"] == null && joinCode() != null -> Response.html(200, JOIN_PAGE, head = request.method == "HEAD")
                    else -> Response.html(403, EXPIRED_PAGE)
                }
            }
            "/join" -> {
                if (request.method != "POST") throw HttpError(405, "method")
                val form = readSmallForm(request, body, startedAt, MAX_ANSWER_BYTES)
                if (!allowJoin(clientAddress)) throw HttpError(429, "rate", listOf("Retry-After" to "60"))
                val code = joinCode() ?: throw HttpError(404, "not_found")
                val given = form["code"]?.filter(Char::isDigit).orEmpty()
                if (!java.security.MessageDigest.isEqual(code.toByteArray(), given.toByteArray())) {
                    synchronized(joinAttempts) {
                        joinFailures++
                        if (joinFailures >= MAX_JOIN_FAILURES) {
                            joinLockedUntil = nowMillis() + JOIN_LOCK_MS
                            joinFailures = 0
                        }
                    }
                    throw HttpError(403, "code")
                }
                Response.json(200, """{"key":${jsonString(key)}}""")
            }
            "/photos" -> {
                if (request.method != "POST") throw HttpError(405, "method")
                receivePhoto(request, body, clientAddress, startedAt)
            }
            "/videos" -> {
                if (request.method != "POST") throw HttpError(405, "method")
                receiveVideo(request, body, clientAddress)
            }
            // Chi ha mandato una foto la può togliere, con la ricevuta avuta all'invio.
            "/photos/delete" -> {
                if (request.method != "POST") throw HttpError(405, "method")
                if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
                if (!allowAction(clientAddress)) throw HttpError(429, "rate", listOf("Retry-After" to "5"))
                val form = readSmallForm(request, body, startedAt, MAX_ACTION_BYTES)
                val id = form["id"]?.takeIf { it.length in 8..64 && it.all(::isIdChar) } ?: throw HttpError(400, "invalid")
                if (!receiptMatches(id, form["receipt"])) throw HttpError(403, "receipt")
                if (!onDeletePhoto(id)) throw HttpError(404, "not_found")
                Response.json(200, """{"status":"deleted"}""")
            }
            // Le foto approvate della festa, da portare a casa: un archivio ZIP scritto al momento.
            "/party/album.zip" -> {
                if (request.method != "GET" && request.method != "HEAD") throw HttpError(405, "method")
                if (!keyMatches(request.query["k"])) throw HttpError(401, "key")
                val entries = extras?.album() ?: throw HttpError(404, "not_found")
                if (entries.isEmpty()) throw HttpError(404, "empty")
                Response.stream(
                    200, "application/zip", ZipStream.length(entries),
                    listOf("Content-Disposition" to "attachment; filename=\"FotoFesta.zip\""),
                    request.method == "HEAD",
                ) { output -> ZipStream.write(entries, output) }
            }
            "/game" -> {
                if (request.method != "GET") throw HttpError(405, "method")
                if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
                Response.json(200, gameJson(game?.current(), request.query["player"]))
            }
            "/game/answer" -> {
                if (request.method != "POST") throw HttpError(405, "method")
                receiveAnswer(request, body, startedAt)
            }
            "/party" -> {
                if (request.method != "GET") throw HttpError(405, "method")
                if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
                val features = hub?.featuresJson() ?: """{"reactions":false,"messages":false,"moderated":false}"""
                val player = request.query["player"]
                lastGuestContact = nowMillis()
                val party = hub?.partyJson(player) ?: """{"announcement":null,"director":null}"""
                val hostOn = hostRemote?.pin() != null
                val playJson = play?.phoneJson(player) ?: "null"
                val disco = extras?.discoJson() ?: "null"
                val wall = extras?.phoneWallJson(player) ?: "null"
                val setup = extras?.setupJson() ?: "null"
                Response.json(200, """{"features":$features,"game":${gameJson(game?.current(), player)},"party":$party,"host":$hostOn,"play":$playJson,"disco":$disco,"phoneWall":$wall,"setup":$setup}""")
            }
            "/party/logo" -> {
                if (request.method != "GET") throw HttpError(405, "method")
                if (!keyMatches(request.query["k"])) throw HttpError(401, "key")
                val bytes = extras?.logo() ?: throw HttpError(404, "not_found")
                Response(200, imageType(bytes), bytes, listOf("Cache-Control" to "private, max-age=60"))
            }
            // Logo e sponsor scelti dalla regia sul telefono, già ridotti dalla pagina.
            "/party/host/upload" -> {
                if (request.method != "POST") throw HttpError(405, "method")
                if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
                val remote = hostRemote ?: throw HttpError(404, "not_found")
                if (!remote.isHost(request.headers["x-fotofesta-host"])) throw HttpError(401, "host")
                val kind = request.query["kind"]?.takeIf { it == "logo" || it == "sponsor" } ?: throw HttpError(400, "invalid")
                val length = request.headers["content-length"]?.trim()?.toIntOrNull() ?: throw HttpError(411, "length")
                if (length <= 0) throw HttpError(400, "empty")
                if (length > PartySetup.MAX_IMAGE_BYTES) throw HttpError(413, "too_large")
                val bytes = readBody(body, length, deadline = startedAt + bodyDeadlineMillis, now = nowMillis)
                if (ImageFormat.sniff(bytes) == null) throw HttpError(415, "unsupported")
                if (extras?.hostUpload(kind, bytes) != true) throw HttpError(507, "full")
                Response.json(200, """{"status":"ok"}""")
            }
            "/party/host/sponsor" -> {
                if (request.method != "GET") throw HttpError(405, "method")
                if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
                val remote = hostRemote ?: throw HttpError(404, "not_found")
                if (!remote.isHost(request.headers["x-fotofesta-host"])) throw HttpError(401, "host")
                val bytes = extras?.sponsor(request.query["id"].orEmpty()) ?: throw HttpError(404, "not_found")
                Response(200, imageType(bytes), bytes)
            }
            "/party/action" -> {
                if (request.method != "POST") throw HttpError(405, "method")
                receiveAction(request, body, clientAddress, startedAt)
            }
            "/party/host/login" -> {
                if (request.method != "POST") throw HttpError(405, "method")
                if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
                val remote = hostRemote ?: throw HttpError(404, "not_found")
                val form = readSmallForm(request, body, startedAt, MAX_ANSWER_BYTES)
                when (val result = remote.login(form["pin"], clientAddress.hostAddress.orEmpty())) {
                    is HostRemote.Login.Accepted -> Response.json(200, """{"token":"${result.token}"}""")
                    HostRemote.Login.Wrong -> throw HttpError(403, "pin")
                    HostRemote.Login.RateLimited -> throw HttpError(429, "rate", listOf("Retry-After" to "60"))
                    HostRemote.Login.Locked -> throw HttpError(423, "locked", listOf("Retry-After" to "300"))
                    HostRemote.Login.Disabled -> throw HttpError(404, "not_found")
                }
            }
            "/party/host" -> {
                if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
                val remote = hostRemote ?: throw HttpError(404, "not_found")
                val token = request.headers["x-fotofesta-host"]
                when (request.method) {
                    "GET" -> {
                        if (!remote.isHost(token)) throw HttpError(401, "host")
                        Response.json(200, remote.statusJson(hub ?: PartyHub(), extras?.setupHostJson() ?: "null"))
                    }
                    "POST" -> {
                        val form = readSmallForm(request, body, startedAt, MAX_ACTION_BYTES)
                        val (status, json) = remote.handle(token, form)
                        Response.json(status, json)
                    }
                    else -> throw HttpError(405, "method")
                }
            }
            "/party/host/photo" -> {
                if (request.method != "GET") throw HttpError(405, "method")
                if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
                val remote = hostRemote ?: throw HttpError(404, "not_found")
                if (!remote.isHost(request.headers["x-fotofesta-host"])) throw HttpError(401, "host")
                val id = request.query["id"].orEmpty()
                val bytes = id.takeIf(remote::canShow)?.let(hostPhoto) ?: throw HttpError(404, "not_found")
                Response(200, imageType(bytes), bytes)
            }
            // Schermo aggiuntivo (un'altra TV della casa): con il PIN della regia riceve la chiave
            // della festa e mostra la parete delle foto. Stessi limiti di tentativi della regia.
            "/screen/login" -> {
                if (request.method != "POST") throw HttpError(405, "method")
                val remote = hostRemote ?: throw HttpError(404, "not_found")
                val form = readSmallForm(request, body, startedAt, MAX_ANSWER_BYTES)
                when (remote.login(form["pin"], clientAddress.hostAddress.orEmpty())) {
                    is HostRemote.Login.Accepted -> Response.json(200, """{"key":${jsonString(key)}}""")
                    HostRemote.Login.Wrong -> throw HttpError(403, "pin")
                    HostRemote.Login.RateLimited -> throw HttpError(429, "rate", listOf("Retry-After" to "60"))
                    HostRemote.Login.Locked -> throw HttpError(423, "locked", listOf("Retry-After" to "300"))
                    HostRemote.Login.Disabled -> throw HttpError(404, "not_found")
                }
            }
            "/door", "/wall" -> {
                if (request.method != "GET" && request.method != "HEAD") throw HttpError(405, "method")
                val pages = extras ?: throw HttpError(404, "not_found")
                if (!keyMatches(request.query["k"])) Response.html(403, EXPIRED_PAGE)
                else Response.html(200, if (request.path == "/door") pages.doorPage() else pages.wallPage(), request.method == "HEAD", MEDIA_PAGE_POLICY)
            }
            // Per <img>, che non manda intestazioni: la chiave è nell'indirizzo, come nella pagina.
            "/wall/state" -> {
                if (request.method != "GET") throw HttpError(405, "method")
                val pages = extras ?: throw HttpError(404, "not_found")
                if (!keyMatches(request.query["k"])) throw HttpError(401, "key")
                Response.json(200, pages.wallState())
            }
            "/wall/photo" -> {
                if (request.method != "GET") throw HttpError(405, "method")
                val pages = extras ?: throw HttpError(404, "not_found")
                if (!keyMatches(request.query["k"])) throw HttpError(401, "key")
                val bytes = pages.wallPhoto(request.query["id"].orEmpty()) ?: throw HttpError(404, "not_found")
                Response(200, imageType(bytes), bytes, listOf("Cache-Control" to "private, max-age=3600"))
            }
            // Silent disco: il brano in corso, a pezzi da un mega (l'audio dei telefoni li chiede uno alla volta).
            "/disco/audio" -> {
                if (request.method != "GET" && request.method != "HEAD") throw HttpError(405, "method")
                val pages = extras ?: throw HttpError(404, "not_found")
                if (!keyMatches(request.query["k"])) throw HttpError(401, "key")
                val track = pages.discoTrack(request.query["t"].orEmpty()) ?: throw HttpError(404, "not_found")
                val range = discoRange(request.headers["range"], track.length, DISCO_CHUNK)
                    ?: throw HttpError(416, "range", listOf("Content-Range" to "bytes */${track.length}"))
                val partial = request.headers["range"] != null || range.last < track.length - 1
                val headers = buildList {
                    add("Accept-Ranges" to "bytes")
                    if (partial) add("Content-Range" to "bytes ${range.first}-${range.last}/${track.length}")
                }
                Response.stream(if (partial) 206 else 200, track.mimeType, range.last - range.first + 1, headers, request.method == "HEAD") { output ->
                    track.open(range.first).use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var left = range.last - range.first + 1
                        while (left > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            left -= read
                        }
                    }
                }
            }
            "/party/qr.svg" -> {
                if (request.method != "GET") throw HttpError(405, "method")
                if (!keyMatches(request.query["k"])) throw HttpError(401, "key")
                val target = when (request.query["for"]) {
                    "door" -> guestUrl() + "&arrivo=1"
                    "wall" -> "http://$expectedHost/wall?k=$key"
                    else -> guestUrl()
                }
                Response(200, "image/svg+xml", QrSvg.encode(target).toByteArray(Charsets.UTF_8))
            }
            else -> Response.json(404, """{"error":"not_found"}""")
        }
    }

    /** Al massimo [JOIN_PER_MINUTE] codici al minuto per telefono; dopo molti errori in tutto, una pausa. */
    private fun allowJoin(address: InetAddress): Boolean = synchronized(joinAttempts) {
        val now = nowMillis()
        if (now < joinLockedUntil) return false
        val times = joinAttempts.getOrPut(address) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
        if (times.size >= JOIN_PER_MINUTE) return false
        times.addLast(now)
        true
    }

    /** Ricevuta di una foto: firma della chiave della festa, che il telefono non conosce in anticipo. */
    private fun receiptFor(id: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("photo:$id".toByteArray(Charsets.UTF_8))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(RECEIPT_LENGTH)
    }

    private fun receiptMatches(id: String, receipt: String?): Boolean =
        receipt != null && java.security.MessageDigest.isEqual(receiptFor(id).toByteArray(), receipt.toByteArray())

    /** Indirizzo della pagina della festa, lo stesso del QR sulla TV. */
    private fun guestUrl(): String = "http://$expectedHost/?k=$key"

    private fun imageType(bytes: ByteArray): String = when (ImageFormat.sniff(bytes)) {
        ImageFormat.PNG -> "image/png"
        ImageFormat.WEBP -> "image/webp"
        else -> "image/jpeg"
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
            val player = request.headers["x-fotofesta-player"]?.takeIf(PhoneGame::validPlayer)
            val upload = onPhoto(bytes, sender, player)
            // Ricevuta per togliere la foto: solo chi l'ha mandata la riceve.
            val receipt = upload.photoId?.let { id -> ""","id":${jsonString(id)},"receipt":"${receiptFor(id)}"""" }.orEmpty()
            when (upload.result) {
                UploadResult.SHOWN -> Response.json(200, """{"status":"shown"$receipt}""")
                UploadResult.PENDING -> Response.json(200, """{"status":"pending"$receipt}""")
                UploadResult.FULL -> throw HttpError(507, "full")
                UploadResult.TOO_MANY_PENDING -> throw HttpError(507, "pending_full")
                UploadResult.UNSUPPORTED -> throw HttpError(415, "unsupported")
                UploadResult.LIMIT -> throw HttpError(403, "limit")
                UploadResult.QUIET -> throw HttpError(423, "quiet")
            }
        } finally {
            inFlight.release(size)
        }
        uploadId?.let { id -> synchronized(handledUploads) { handledUploads[id] = response } }
        return response
    }

    /**
     * Video breve: va su disco a pezzi, senza passare dalla RAM e senza contare nel tetto dei byte
     * in arrivo; al massimo [MAX_VIDEO_UPLOADS] alla volta, con più tempo di una foto.
     */
    private fun receiveVideo(request: Request, body: InputStream, clientAddress: InetAddress): Response {
        if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
        val handler = onVideo ?: throw HttpError(404, "not_found")
        if (request.headers.containsKey("transfer-encoding")) throw HttpError(411, "length")
        val length = request.headers["content-length"]?.trim()?.toLongOrNull() ?: throw HttpError(411, "length")
        if (length <= 0) throw HttpError(400, "empty")
        if (length > MAX_VIDEO_BYTES) throw HttpError(413, "too_large")
        val uploadId = request.headers["x-fotofesta-upload"]?.takeIf { it.length in 8..64 && it.all(::isIdChar) }
        uploadId?.let { id -> synchronized(handledUploads) { handledUploads[id] }?.let { return it } }
        if (!allowUpload(clientAddress)) throw HttpError(429, "rate", listOf("Retry-After" to "10"))
        if (!videoSlots.tryAcquire()) throw HttpError(503, "busy", listOf("Retry-After" to "10"))
        val response = try {
            val sender = request.headers["x-fotofesta-sender"]?.let(::decodeSender)
            val player = request.headers["x-fotofesta-player"]?.takeIf(PhoneGame::validPlayer)
            val deadline = nowMillis() + VIDEO_DEADLINE_MS
            // Il tempo totale vale anche qui: una connessione lentissima non tiene il posto per sempre.
            val limited = object : InputStream() {
                override fun read(): Int = if (nowMillis() > deadline) throw IOException("deadline") else body.read()
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int =
                    if (nowMillis() > deadline) throw IOException("deadline") else body.read(buffer, offset, count)
            }
            val upload = handler(limited, length, sender, player)
            val receipt = upload.photoId?.let { id -> ""","id":${jsonString(id)},"receipt":"${receiptFor(id)}"""" }.orEmpty()
            when (upload.result) {
                UploadResult.SHOWN -> Response.json(200, """{"status":"shown"$receipt}""")
                UploadResult.PENDING -> Response.json(200, """{"status":"pending"$receipt}""")
                UploadResult.FULL, UploadResult.TOO_MANY_PENDING -> throw HttpError(507, "full")
                UploadResult.UNSUPPORTED -> throw HttpError(415, "unsupported")
                UploadResult.LIMIT -> throw HttpError(403, "limit")
                UploadResult.QUIET -> throw HttpError(423, "quiet")
            }
        } finally {
            videoSlots.release()
        }
        uploadId?.let { id -> synchronized(handledUploads) { handledUploads[id] = response } }
        return response
    }

    /** Domanda in corso per la pagina dei telefoni, con i punti del telefono che la chiede. */
    private fun gameJson(snapshot: PhoneGame.Snapshot?, player: String?): String {
        if (snapshot == null) return """{"round":null}"""
        val points = player?.takeIf(PhoneGame::validPlayer)?.let { game?.pointsOf(it) } ?: 0
        return buildString {
            append("""{"round":""").append(jsonString(snapshot.round))
            append(""","question":""").append(jsonString(snapshot.question))
            append(""","choices":[""").append(snapshot.choices.joinToString(",") { jsonString(it) }).append(']')
            append(""","open":""").append(snapshot.open)
            append(""","answer":""").append(snapshot.answer?.toString() ?: "null")
            append(""","poll":""").append(snapshot.poll)
            append(""","tally":""").append(snapshot.tally?.joinToString(",", "[", "]") ?: "null")
            append(""","points":""").append(points).append('}')
        }
    }

    /** Risposta di un telefono, in un piccolo modulo: round, choice, player e name. */
    private fun receiveAnswer(request: Request, body: InputStream, startedAt: Long): Response {
        if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
        val game = game ?: throw HttpError(404, "not_found")
        if (request.headers.containsKey("transfer-encoding")) throw HttpError(411, "length")
        val length = request.headers["content-length"]?.trim()?.toIntOrNull() ?: throw HttpError(411, "length")
        if (length <= 0) throw HttpError(400, "empty")
        if (length > MAX_ANSWER_BYTES) throw HttpError(413, "too_large")
        val form = parseForm(readBody(body, length, deadline = startedAt + bodyDeadlineMillis, now = nowMillis).toString(Charsets.UTF_8))
        val round = form["round"] ?: throw HttpError(400, "invalid")
        val choice = form["choice"]?.toIntOrNull() ?: throw HttpError(400, "invalid")
        val player = form["player"] ?: throw HttpError(400, "invalid")
        return when (game.answer(round, choice, player, GuestSender.clean(form["name"]))) {
            PhoneGame.AnswerResult.ACCEPTED -> Response.json(200, """{"status":"ok"}""")
            PhoneGame.AnswerResult.CLOSED -> throw HttpError(409, "closed")
            PhoneGame.AnswerResult.INVALID -> throw HttpError(400, "invalid")
        }
    }

    /** Reazione o dedica di un telefono, in un piccolo modulo con "type". */
    private fun receiveAction(request: Request, body: InputStream, clientAddress: InetAddress, startedAt: Long): Response {
        if (!keyMatches(request.headers["x-fotofesta-key"])) throw HttpError(401, "key")
        val hub = hub ?: throw HttpError(404, "not_found")
        // Il limite per telefono di PartyHub si aggira cambiando identificativo: qui conta l'indirizzo.
        if (!allowAction(clientAddress)) throw HttpError(429, "rate", listOf("Retry-After" to "5"))
        val form = readSmallForm(request, body, startedAt, MAX_ACTION_BYTES)
        val (status, json) = when {
            form["type"] == "wall" -> when {
                hub.quietLeftMillis() > 0 -> 423 to """{"error":"quiet"}"""
                else -> extras?.phoneWallAction(form) ?: (404 to """{"error":"not_found"}""")
            }
            form["type"] != "play" -> hub.handle(form)
            play == null -> 404 to """{"error":"not_found"}"""
            // Nei tre minuti senza telefoni aspettano anche i giochi.
            hub.quietLeftMillis() > 0 -> 423 to """{"error":"quiet"}"""
            else -> play.handle(form)
        }
        return Response.json(status, json, if (status == 429) listOf("Retry-After" to "3") else emptyList())
    }

    private fun readSmallForm(request: Request, body: InputStream, startedAt: Long, limit: Int): Map<String, String> {
        if (request.headers.containsKey("transfer-encoding")) throw HttpError(411, "length")
        val length = request.headers["content-length"]?.trim()?.toIntOrNull() ?: throw HttpError(411, "length")
        if (length <= 0) throw HttpError(400, "empty")
        if (length > limit) throw HttpError(413, "too_large")
        return parseForm(readBody(body, length, deadline = startedAt + bodyDeadlineMillis, now = nowMillis).toString(Charsets.UTF_8))
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

    /** Al massimo [MAX_ACTIONS_PER_MINUTE] reazioni e dediche al minuto dallo stesso indirizzo. */
    private fun allowAction(address: InetAddress): Boolean = synchronized(recentActions) {
        val now = nowMillis()
        val times = recentActions.getOrPut(address) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.peekFirst()!! > 60_000) times.pollFirst()
        if (times.size >= MAX_ACTIONS_PER_MINUTE) return false
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
        /** Corpo scritto a pezzi (brani della silent disco), al posto di [body]. */
        val stream: ((OutputStream) -> Unit)? = null,
        val length: Long = body.size.toLong(),
    ) {
        companion object {
            fun stream(status: Int, type: String, length: Long, headers: List<Pair<String, String>>, head: Boolean, write: (OutputStream) -> Unit) =
                Response(status, type, ByteArray(0), headers, head, write, length)

            fun json(status: Int, body: String, headers: List<Pair<String, String>> = emptyList()) =
                Response(status, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8), headers)

            fun html(status: Int, body: String, head: Boolean = false, policy: String = PAGE_POLICY) = Response(
                status,
                "text/html; charset=utf-8",
                body.toByteArray(Charsets.UTF_8),
                listOf("Content-Security-Policy" to policy),
                head,
            )
        }
    }

    private fun write(output: OutputStream, response: Response) {
        val headers = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.length).append("\r\n")
            append("Connection: close\r\n")
            if (response.extraHeaders.none { it.first.equals("Cache-Control", ignoreCase = true) }) append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            response.extraHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        if (response.head) return
        val stream = response.stream
        if (stream != null) stream(output) else output.write(response.body)
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
            "style-src 'unsafe-inline'; img-src blob: data:; media-src 'self'; connect-src 'self'; " +
            "form-action 'none'; base-uri 'none'; frame-ancestors 'none'"

        /** Parete e ingresso mostrano immagini e QR serviti dalla TV stessa. */
        private const val MEDIA_PAGE_POLICY = "default-src 'none'; script-src 'unsafe-inline'; " +
            "style-src 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'self'; " +
            "form-action 'none'; base-uri 'none'; frame-ancestors 'none'"

        private const val RECEIPT_LENGTH = 22

        /** Nome della TV nella rete locale (mDNS), per chi scrive l'indirizzo a mano. */
        const val LOCAL_NAME = "festa.local"
        private const val JOIN_PER_MINUTE = 5
        private const val MAX_JOIN_FAILURES = 30
        private const val JOIN_LOCK_MS = 5 * 60_000L

        private const val JOIN_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>FotoFesta</title>
<style>body{font:18px/1.5 system-ui,sans-serif;margin:0;background:#0d0f13;color:#fff}main{max-width:420px;margin:0 auto;padding:40px 20px}
h1{font-size:1.6rem;margin:.2em 0}input{width:100%;box-sizing:border-box;font:700 2rem/1 ui-monospace,monospace;letter-spacing:.4em;text-align:center;padding:14px;border-radius:16px;border:2px solid #343945;background:#1b1e25;color:#fff}
button{width:100%;margin-top:14px;min-height:56px;border:0;border-radius:16px;background:linear-gradient(120deg,#ff7a57,#ff4f9a,#8b6cff);color:#1a0b06;font:800 1.1rem system-ui,sans-serif}p{color:#c8cdd6}#e{color:#ff9c85;min-height:1.5em}</style></head>
<body><main><p style="color:#ff7a57;font-weight:800;letter-spacing:.14em">FOTOFESTA</p><h1 id="t"></h1><p id="b"></p>
<input id="c" inputmode="numeric" maxlength="4" autocomplete="one-time-code"><button id="go"></button><p id="e"></p></main>
<script>
var L={it:["Scrivi il codice della festa","Lo trovi sulla TV, accanto al QR.","Entra","Codice sbagliato: riprova.","Troppi tentativi: aspetta un minuto."],
en:["Type the party code","You'll find it on the TV, next to the QR code.","Join","Wrong code: try again.","Too many attempts: wait a minute."],
fr:["Saisissez le code de la fête","Vous le trouverez sur la TV, à côté du QR.","Entrer","Code incorrect : réessayez.","Trop d'essais : attendez une minute."],
es:["Escribe el código de la fiesta","Lo encontrarás en la TV, junto al QR.","Entrar","Código incorrecto: inténtalo de nuevo.","Demasiados intentos: espera un minuto."],
de:["Gib den Party-Code ein","Du findest ihn am Fernseher neben dem QR-Code.","Los","Falscher Code: Versuch es noch einmal.","Zu viele Versuche: Warte eine Minute."]};
var T=L[(navigator.language||"en").slice(0,2)]||L.en;document.getElementById("t").textContent=T[0];document.getElementById("b").textContent=T[1];document.getElementById("go").textContent=T[2];
function go(){var c=document.getElementById("c").value;fetch("/join",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:"code="+encodeURIComponent(c)})
.then(function(r){if(r.ok)return r.json().then(function(j){location.replace("/?k="+encodeURIComponent(j.key))});document.getElementById("e").textContent=r.status==429?T[4]:T[3]})
.catch(function(){document.getElementById("e").textContent=T[3]})}
document.getElementById("go").onclick=go;document.getElementById("c").onkeydown=function(e){if(e.key==="Enter")go()};
</script></body></html>"""

        /** Video brevi degli invitati: un minuto di un telefono sta comodamente sotto questo limite. */
        const val MAX_VIDEO_BYTES = 60L * 1024 * 1024
        private const val MAX_VIDEO_UPLOADS = 2
        private const val VIDEO_DEADLINE_MS = 240_000L

        /** Pezzi di brano per la silent disco: abbastanza per qualche secondo, mai un thread bloccato a lungo. */
        private const val DISCO_CHUNK = 1024L * 1024

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

        /** Scarta al massimo [MAX_DRAIN_BYTES] ed entro [deadline]: un client che manda un byte alla volta non tiene occupato il worker. */
        private fun drain(input: InputStream, deadline: Long, now: () -> Long) {
            val buffer = ByteArray(8 * 1024)
            var total = 0
            try {
                while (total < MAX_DRAIN_BYTES && now() < deadline) {
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

        /** Risposte dei telefoni: poche decine di byte, con margine per nomi lunghi. */
        private const val MAX_ANSWER_BYTES = 1_024

        /** Reazioni e dediche: una dedica di 160 caratteri codificata occupa al massimo circa 1,5 kB. */
        /** Basta per un disegno dei giochi (al massimo [PartyPlay.MAX_DRAWING_LENGTH] caratteri). */
        private const val MAX_ACTION_BYTES = 24_576
        private const val MAX_ACTIONS_PER_MINUTE = 150

        /** Campi di un modulo "a=1&b=2"; i valori malformati fanno rifiutare la richiesta. */
        internal fun parseForm(body: String): Map<String, String> = try {
            body.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val name = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                name to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
        } catch (_: IllegalArgumentException) {
            throw HttpError(400, "invalid")
        }

        /** Stringa JSON tra virgolette, con i caratteri speciali protetti. */
        internal fun jsonString(value: String): String = buildString {
            append('"')
            for (char in value) {
                when {
                    char == '"' -> append("\\\"")
                    char == '\\' -> append("\\\\")
                    char < ' ' || char == '\u2028' || char == '\u2029' -> append("\\u%04x".format(char.code))
                    else -> append(char)
                }
            }
            append('"')
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            416 -> "Range Not Satisfiable"
            421 -> "Misdirected Request"
            423 -> "Locked"
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

    /** Testo ripulito: spazi compattati, niente caratteri invisibili, al massimo [maxLength] caratteri. */
    fun clean(value: String?, maxLength: Int = MAX_LENGTH): String? {
        if (value == null) return null
        val result = StringBuilder()
        var count = 0
        var pendingSpace = false
        var index = 0
        while (index < value.length && count < maxLength) {
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
                        if (count >= maxLength) break
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
