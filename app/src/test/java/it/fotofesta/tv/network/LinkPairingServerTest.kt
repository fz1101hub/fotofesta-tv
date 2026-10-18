package it.fotofesta.tv.network

import it.fotofesta.tv.icloud.ICloudLink
import it.fotofesta.tv.icloud.ICloudPairingTexts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder

class LinkPairingServerTest {
    private val received = mutableListOf<String>()
    private val server = LinkPairingServer("chiave-segreta", ICloudPairingTexts::forLanguage) { link ->
        ICloudLink.token(link)?.also { synchronized(received) { received += it } } != null
    }
    private val loopback = InetAddress.getLoopbackAddress()
    private val port = server.start(loopback)
    private val host = "127.0.0.1:$port"

    @After
    fun close() = server.close()

    private fun send(raw: String): Pair<Int, String> = Socket(loopback, port).use { socket ->
        socket.getOutputStream().write(raw.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().flush()
        val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        text.substringAfter(' ').substringBefore(' ').toInt() to text.substringAfter("\r\n\r\n")
    }

    private fun get(target: String, hostHeader: String = host, language: String = "it-IT,it;q=0.9") =
        send("GET $target HTTP/1.1\r\nHost: $hostHeader\r\nAccept-Language: $language\r\n\r\n")

    private fun post(form: String, hostHeader: String = host) =
        send("POST /link HTTP/1.1\r\nHost: $hostHeader\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${form.toByteArray().size}\r\n\r\n$form")

    private fun form(key: String, link: String) = "k=${URLEncoder.encode(key, "UTF-8")}&link=${URLEncoder.encode(link, "UTF-8")}"

    @Test
    fun servesTheFormOnlyWithTheKeyAndTheRightHost() {
        val (status, page) = get("/?k=chiave-segreta")
        assertEquals(200, status)
        assertTrue(page.contains("<form method=\"post\" action=\"/link\">"))
        assertTrue(page.contains("Sito web pubblico"))
        assertEquals(403, get("/?k=sbagliata").first)
        assertEquals(421, get("/?k=chiave-segreta", hostHeader = "evil.example").first)
        assertEquals(404, get("/altro").first)
        assertTrue(get("/?k=chiave-segreta", language = "de-DE").second.contains("Öffentliche Website"))
        assertTrue(get("/?k=chiave-segreta", language = "pt-BR").second.contains("Public Website"))
    }

    @Test
    fun receivesAValidLinkAndRefusesTheRest() {
        val (status, page) = post(form("chiave-segreta", "https://www.icloud.com/sharedalbum/#B0aGWZuqDGKfHtd"))
        assertEquals(200, status)
        assertTrue(page.contains("guarda la TV") || page.contains("look at the TV"))
        assertEquals(listOf("B0aGWZuqDGKfHtd"), received)
        assertEquals(400, post(form("chiave-segreta", "https://example.com/foto")).first)
        assertEquals(403, post(form("sbagliata", "https://www.icloud.com/sharedalbum/#B0aGWZuqDGKfHtd")).first)
        assertEquals(413, post(form("chiave-segreta", "x".repeat(5_000))).first)
        assertEquals(1, received.size)
    }

    @Test
    fun errorPagesDoNotRepeatTheKey() {
        val (noLength, noLengthPage) = send("POST /link HTTP/1.1\r\nHost: $host\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n")
        assertEquals(411, noLength)
        assertFalse(noLengthPage.contains("chiave-segreta"))
        assertFalse(noLengthPage.contains("<form"))
        val (tooBig, tooBigPage) = post(form("chiave-segreta", "x".repeat(5_000)))
        assertEquals(413, tooBig)
        assertFalse(tooBigPage.contains("chiave-segreta"))
        assertFalse(tooBigPage.contains("<form"))
        // La chiave compare solo nella pagina aperta con il QR.
        assertTrue(get("/?k=chiave-segreta").second.contains("chiave-segreta"))
        assertFalse(get("/?k=sbagliata").second.contains("chiave-segreta"))
    }

    @Test
    fun stopClosesOpenConnectionsAndDropsLateLinks() {
        val socket = Socket(loopback, port)
        socket.soTimeout = 5_000
        // Connessione già accettata, in attesa della richiesta.
        Thread.sleep(300)
        server.stop()
        val raw = form("chiave-segreta", "https://www.icloud.com/sharedalbum/#B0aGWZuqDGKfHtd")
        val status = runCatching {
            socket.getOutputStream().write("POST /link HTTP/1.1\r\nHost: $host\r\nContent-Length: ${raw.length}\r\n\r\n$raw".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8).substringAfter(' ').substringBefore(' ').toInt()
        }.getOrNull()
        socket.close()
        assertTrue("stato $status", status == null || status == 403)
        assertTrue(received.isEmpty())
    }

    @Test
    fun limitsTheConnectionsOfOnePhone() {
        val idle = List(LinkPairingServer.MAX_CONNECTIONS_PER_CLIENT) { Socket(loopback, port) }
        try {
            Thread.sleep(300)
            assertEquals(429, get("/?k=chiave-segreta").first)
        } finally {
            idle.forEach { it.close() }
        }
        // Chiuse le altre, la pagina torna.
        var status = 0
        for (attempt in 1..20) {
            status = get("/?k=chiave-segreta").first
            if (status == 200) break
            Thread.sleep(100)
        }
        assertEquals(200, status)
    }

    @Test
    fun escapesTextAndPicksTheLanguage() {
        assertEquals("&lt;b&gt; &amp; &quot;x&quot; &#39;y&#39;", LinkPairingServer.escape("<b> & \"x\" 'y'"))
        assertEquals("fr", LinkPairingServer.language("fr-CA,fr;q=0.9,en;q=0.8", setOf("it", "en", "fr")))
        assertEquals("en", LinkPairingServer.language(null, setOf("it", "en")))
        assertFalse(LinkPairingServer.language("xx", setOf("it")) == "xx")
    }
}
