package it.fotofesta.tv.guests

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
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class GuestUploadServerTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val received = CopyOnWriteArrayList<Pair<ByteArray, String?>>()
    private var result = GuestUploadServer.UploadResult.SHOWN
    private lateinit var server: GuestUploadServer
    private var port = 0

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

    @Before
    fun setUp() {
        server = GuestUploadServer(
            key = KEY,
            page = { "<html>pagina</html>" },
            onPhoto = { bytes, sender ->
                received += bytes to sender
                result
            },
            maxUploadBytes = 1_000,
            readTimeoutMillis = 2_000,
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
    ): Reply {
        val head = buildString {
            append("POST /photos HTTP/1.1\r\nHost: $host\r\nContent-Type: image/jpeg\r\n")
            append("Content-Length: $length\r\n")
            if (key != null) append("X-FotoFesta-Key: $key\r\n")
            if (sender != null) append("X-FotoFesta-Sender: $sender\r\n")
            append("\r\n")
        }
        return send(head.toByteArray() + body)
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
        try {
            Socket().use { it.connect(InetSocketAddress(loopback, port), 1_000) }
            fail("La porta dovrebbe essere chiusa")
        } catch (_: IOException) {
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
