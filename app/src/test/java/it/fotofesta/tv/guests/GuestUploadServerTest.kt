package it.fotofesta.tv.guests

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class GuestUploadServerTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val received = CopyOnWriteArrayList<Pair<ByteArray, String?>>()
    private var result = GuestUploadServer.UploadResult.SHOWN
    private lateinit var server: GuestUploadServer
    private var port = 0

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

    private var failures = 0
    private val game = PhoneGame()
    private val hub = PartyHub()

    @Before
    fun setUp() = startServer()

    private fun startServer(
        maxUploadsPerMinute: Int = 100,
        headerDeadlineMillis: Long = GuestUploadServer.HEADER_DEADLINE_MS,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    ) {
        if (::server.isInitialized) server.stop()
        server = GuestUploadServer(
            key = KEY,
            page = { "<html>pagina</html>" },
            onPhoto = { bytes, sender ->
                received += bytes to sender
                result
            },
            onFailure = { failures++ },
            game = game,
            hub = hub,
            maxUploadBytes = 1_000,
            readTimeoutMillis = 2_000,
            headerDeadlineMillis = headerDeadlineMillis,
            maxUploadsPerMinute = maxUploadsPerMinute,
            nowMillis = nowMillis,
        )
        port = server.start(loopback, preferredPort = 0)
    }

    @After
    fun tearDown() = server.stop()

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun send(raw: ByteArray): Reply {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, port), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().write(raw)
            socket.getOutputStream().flush()
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val head = response.substringBefore("\r\n\r\n")
            val lines = head.split("\r\n")
            val status = lines.first().split(' ')[1].toInt()
            val headers = lines.drop(1).associate { line ->
                line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
            }
            return Reply(status, headers, response.substringAfter("\r\n\r\n"))
        }
    }

    private fun get(target: String, host: String = "127.0.0.1:$port") =
        send("GET $target HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())

    private fun post(
        body: ByteArray,
        key: String? = KEY,
        sender: String? = null,
        length: Long = body.size.toLong(),
        host: String = "127.0.0.1:$port",
        uploadId: String? = null,
    ): Reply {
        val head = buildString {
            append("POST /photos HTTP/1.1\r\nHost: $host\r\nContent-Type: image/jpeg\r\n")
            append("Content-Length: $length\r\n")
            if (key != null) append("X-FotoFesta-Key: $key\r\n")
            if (sender != null) append("X-FotoFesta-Sender: $sender\r\n")
            if (uploadId != null) append("X-FotoFesta-Upload: $uploadId\r\n")
            append("\r\n")
        }
        return send(head.toByteArray() + body)
    }

    private fun getGame(key: String? = KEY, player: String? = null): Reply {
        val target = if (player != null) "/game?player=$player" else "/game"
        val keyHeader = if (key != null) "X-FotoFesta-Key: $key\r\n" else ""
        return send("GET $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$keyHeader\r\n".toByteArray())
    }

    private fun answer(form: String, key: String? = KEY): Reply {
        val body = form.toByteArray()
        val keyHeader = if (key != null) "X-FotoFesta-Key: $key\r\n" else ""
        val head = "POST /game/answer HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.size}\r\n$keyHeader\r\n"
        return send(head.toByteArray() + body)
    }

    @Test
    fun `phones read the current question with the key`() {
        assertEquals(401, getGame(key = null).status)
        assertEquals(JSONObject.NULL, JSONObject(getGame().body).get("round"))
        game.ask("Chi l'ha \"scattata\"?", listOf("Giulia", "Marco"), correct = 1)
        val question = JSONObject(getGame().body)
        assertEquals(game.current()!!.round, question.getString("round"))
        assertEquals("Chi l'ha \"scattata\"?", question.getString("question"))
        assertEquals("Marco", question.getJSONArray("choices").getString(1))
        assertTrue(question.getBoolean("open"))
        assertTrue(question.isNull("answer"))
    }

    @Test
    fun `phones answer and see the result after the reveal`() {
        game.ask("?", listOf("A", "B"), correct = 1)
        val round = game.current()!!.round
        val reply = answer("round=$round&choice=1&player=telefono-1&name=Giulia%20B")
        assertEquals(200, reply.status)
        assertEquals(1, game.answerCount())
        game.reveal()
        val result = JSONObject(getGame(player = "telefono-1").body)
        assertEquals(1, result.getInt("answer"))
        assertEquals(1, result.getInt("points"))
        assertEquals("Giulia B", game.leaderboard().single().name)
        assertEquals(409, answer("round=$round&choice=0&player=telefono-2").status)
    }

    @Test
    fun `bad answers are refused`() {
        game.ask("?", listOf("A", "B"), correct = 0)
        val round = game.current()!!.round
        assertEquals(401, answer("round=$round&choice=0&player=telefono-1", key = "sbagliata").status)
        assertEquals(400, answer("round=$round&choice=9&player=telefono-1").status)
        assertEquals(400, answer("round=$round&player=telefono-1").status)
        assertEquals(400, answer("round=$round&choice=0&player=telefono-1&name=%ZZ").status)
        assertEquals(413, answer("round=$round&choice=0&player=telefono-1&name=" + "x".repeat(2_000)).status)
        assertEquals(405, send("GET /game/answer HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray()).status)
        assertEquals(0, game.answerCount())
    }

    private fun action(form: String, key: String? = KEY): Reply {
        val body = form.toByteArray()
        val keyHeader = if (key != null) "X-FotoFesta-Key: $key\r\n" else ""
        val head = "POST /party/action HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.size}\r\n$keyHeader\r\n"
        return send(head.toByteArray() + body)
    }

    @Test
    fun `party state has the features and the current game`() {
        val keyHeader = "X-FotoFesta-Key: $KEY\r\n"
        assertEquals(401, send("GET /party HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray()).status)
        game.askPoll("Dove andranno in viaggio?", listOf("Mare", "Montagna"))
        val state = JSONObject(send("GET /party?player=telefono-1 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$keyHeader\r\n".toByteArray()).body)
        assertTrue(state.getJSONObject("features").getBoolean("reactions"))
        val poll = state.getJSONObject("game")
        assertTrue(poll.getBoolean("poll"))
        assertTrue(poll.isNull("tally"))
        game.answer(poll.getString("round"), 1, "telefono-1", null)
        game.reveal()
        val closed = JSONObject(send("GET /party HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$keyHeader\r\n".toByteArray()).body)
        assertEquals(1, closed.getJSONObject("game").getJSONArray("tally").getInt(1))
    }

    @Test
    fun `phones send reactions and messages`() {
        hub.showing("foto-1")
        assertEquals(200, action("type=react&player=telefono-1&reaction=0").status)
        assertEquals(1, hub.reactionCount("foto-1"))
        assertEquals(200, action("type=message&player=telefono-1&name=Giulia&text=Auguri%20a%20tutti").status)
        assertEquals("Auguri a tutti", hub.messages().single().text)
        assertEquals(401, action("type=react&player=telefono-1&reaction=0", key = "sbagliata").status)
        assertEquals(400, action("type=react&player=telefono-1&reaction=9").status)
        assertEquals(413, action("type=message&player=telefono-1&text=" + "x".repeat(5_000)).status)
        assertEquals(405, get("/party/action").status)
    }

    @Test
    fun `json strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", GuestUploadServer.jsonString("a\"b\\c\n"))
        assertEquals("\"Città\"", GuestUploadServer.jsonString("Città"))
    }

    @Test
    fun `page requires the key from the QR code`() {
        val page = get("/?k=$KEY")
        assertEquals(200, page.status)
        assertEquals("<html>pagina</html>", page.body)
        assertTrue(page.headers.getValue("content-security-policy").contains("default-src 'none'"))
        assertEquals("no-store", page.headers["cache-control"])

        assertEquals(403, get("/").status)
        assertEquals(403, get("/?k=wrong").status)
    }

    @Test
    fun `photo with the key is handed over with the sender`() {
        val reply = post(jpeg, sender = "Zia%20Maria%20%F0%9F%8E%89")
        assertEquals(200, reply.status)
        assertEquals("""{"status":"shown"}""", reply.body)
        assertArrayEquals(jpeg, received.single().first)
        assertEquals("Zia Maria 🎉", received.single().second)
    }

    @Test
    fun `pending and full results are reported to the phone`() {
        result = GuestUploadServer.UploadResult.PENDING
        assertEquals("""{"status":"pending"}""", post(jpeg).body)
        result = GuestUploadServer.UploadResult.FULL
        assertEquals(507, post(jpeg).status)
        result = GuestUploadServer.UploadResult.UNSUPPORTED
        assertEquals(415, post(jpeg).status)
        result = GuestUploadServer.UploadResult.TOO_MANY_PENDING
        val tooMany = post(jpeg)
        assertEquals(507, tooMany.status)
        assertEquals("""{"error":"pending_full"}""", tooMany.body)
    }

    @Test
    fun `a repeated upload is stored only once`() {
        assertEquals(200, post(jpeg, uploadId = "abcdef123456").status)
        assertEquals(200, post(jpeg, uploadId = "abcdef123456").status)
        assertEquals(1, received.size)
        assertEquals(200, post(jpeg, uploadId = "other-upload-1").status)
        assertEquals(2, received.size)
    }

    @Test
    fun `uploads from one phone are rate limited`() {
        startServer(maxUploadsPerMinute = 2)
        assertEquals(200, post(jpeg).status)
        assertEquals(200, post(jpeg).status)
        val limited = post(jpeg)
        assertEquals(429, limited.status)
        assertEquals("10", limited.headers["retry-after"])
        assertEquals(2, received.size)
    }

    @Test
    fun `slow requests are dropped after the header deadline`() {
        // Orologio finto: ogni lettura sembra durare un secondo, il limite è di 5 secondi.
        var clock = 0L
        startServer(headerDeadlineMillis = 5_000, nowMillis = { clock += 1_000; clock })
        Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, port), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET /?k=$KEY HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
            assertTrue(socket.getInputStream().readBytes().isEmpty())
        }
    }

    @Test
    fun `a deliberate stop is not reported as a failure`() {
        server.stop()
        assertEquals(0, failures)
    }

    @Test
    fun `uploads without the right key are refused`() {
        assertEquals(401, post(jpeg, key = null).status)
        assertEquals(401, post(jpeg, key = "wrong").status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `files that are not images are refused`() {
        assertEquals(415, post("<script>".toByteArray()).status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `oversized and empty uploads are refused before reading`() {
        assertEquals(413, post(ByteArray(0), length = 5_000).status)
        assertEquals(400, post(ByteArray(0)).status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `requests for another host are refused`() {
        assertEquals(421, get("/?k=$KEY", host = "attacker.example").status)
        assertEquals(421, post(jpeg, host = "attacker.example:$port").status)
        assertEquals(421, send("GET /?k=$KEY HTTP/1.1\r\n\r\n".toByteArray()).status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `unknown paths and methods`() {
        assertEquals(404, get("/favicon.ico").status)
        assertEquals(405, get("/photos").status)
        assertEquals(405, send("DELETE / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray()).status)
    }

    @Test
    fun `chunked uploads are refused`() {
        val raw = "POST /photos HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-FotoFesta-Key: $KEY\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
        assertEquals(411, send(raw.toByteArray()).status)
    }

    @Test
    fun `huge headers are refused`() {
        val raw = "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-Big: ${"a".repeat(20_000)}\r\n\r\n"
        assertEquals(431, send(raw.toByteArray()).status)
    }

    @Test
    fun `stop closes the port`() {
        server.stop()
        assertFalse(server.isRunning)
        // La porta si può occupare di nuovo solo se nessuno è più in ascolto. Una connessione di
        // prova non basterebbe: sulla porta effimera appena liberata il sistema può dare al client
        // la stessa porta come origine, e la connessione a sé stessi riesce anche senza server.
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(loopback, port))
        }
    }

    @Test
    fun `parses request line, query and headers`() {
        val raw = "GET /?k=abc&x=1 HTTP/1.1\r\nHost: tv:8\r\nX-Test:  value \r\n\r\nbody"
        val request = GuestUploadServer.readRequest(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("GET", request.method)
        assertEquals("/", request.path)
        assertEquals("abc", request.query["k"])
        assertEquals("value", request.headers["x-test"])
        assertNull(GuestUploadServer.readRequest(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `conflicting content lengths are rejected`() {
        val raw = "POST /photos HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n"
        try {
            GuestUploadServer.readRequest(ByteArrayInputStream(raw.toByteArray()))
            fail("Intestazioni ambigue accettate")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `host header uses brackets for IPv6`() {
        assertEquals("192.168.1.5:8765", GuestUploadServer.hostHeader(InetAddress.getByName("192.168.1.5"), 8765))
        assertEquals("[::1]:80", GuestUploadServer.hostHeader(InetAddress.getByName("::1"), 80).replace("0:0:0:0:0:0:0:1", "::1"))
    }

    @Test
    fun `image formats are recognised from their first bytes`() {
        assertEquals(ImageFormat.JPEG, ImageFormat.sniff(jpeg))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)
        assertEquals(ImageFormat.PNG, ImageFormat.sniff(png))
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
        assertEquals(ImageFormat.WEBP, ImageFormat.sniff(webp))
        assertNull(ImageFormat.sniff("GIF89a".toByteArray()))
        assertNull(ImageFormat.sniff(ByteArray(2)))
    }

    @Test
    fun `sender names are limited and stripped of hidden characters`() {
        assertNull(GuestSender.clean(null))
        assertNull(GuestSender.clean("   ​ "))
        assertEquals("Anna Rossi", GuestSender.clean(" Anna \t‮Rossi\u0000 "))
        val long = "🎉".repeat(50)
        val cleaned = GuestSender.clean(long)!!
        assertEquals(GuestSender.MAX_LENGTH, cleaned.codePointCount(0, cleaned.length))
        assertEquals("🎉".repeat(GuestSender.MAX_LENGTH), cleaned)
    }

    private companion object {
        const val KEY = "test-key_123"
    }
}
