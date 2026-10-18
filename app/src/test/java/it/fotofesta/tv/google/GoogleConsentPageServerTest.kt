package it.fotofesta.tv.google

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class GoogleConsentPageServerTest {
    private val codes = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private val loopback = InetAddress.getLoopbackAddress()
    private val server = GoogleConsentPageServer(
        key = "chiave",
        page = { "<html>pagina google</html>" },
        authorizationUrl = { returnUrl -> "https://accounts.google.com/o/oauth2/v2/auth?state=" + GoogleAuthRepository.encodeState(returnUrl) },
        onCode = { synchronized(codes) { codes += it } },
        onError = { synchronized(errors) { errors += it } },
    )
    private val pageUrl = server.start(loopback)
    private val host = pageUrl.substringAfter("http://").substringBefore("/")

    @After
    fun close() = server.close()

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun get(target: String, hostHeader: String = host): Reply = Socket(loopback, host.substringAfter(':').toInt()).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: $hostHeader\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
        val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        val head = text.substringBefore("\r\n\r\n").split("\r\n")
        Reply(
            head.first().split(' ')[1].toInt(),
            head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() },
            text.substringAfter("\r\n\r\n"),
        )
    }

    @Test
    fun servesThePageAndTheWayToGoogleOnlyWithTheKey() {
        assertEquals("http://$host/?k=chiave", pageUrl)
        val page = get("/?k=chiave")
        assertEquals(200, page.status)
        assertEquals("<html>pagina google</html>", page.body)
        assertTrue(page.headers.getValue("content-security-policy").contains("connect-src 'self'"))
        val start = get("/start?k=chiave")
        assertEquals(200, start.status)
        val expectedState = GoogleAuthRepository.encodeState("http://$host/google?k=chiave")
        assertEquals("""{"url":"https://accounts.google.com/o/oauth2/v2/auth?state=$expectedState"}""", start.body)
        assertEquals(403, get("/?k=sbagliata").status)
        assertEquals(403, get("/start").status)
        assertEquals(421, get("/?k=chiave", hostHeader = "evil.example").status)
        assertEquals(404, get("/altro?k=chiave").status)
    }

    @Test
    fun theCodeFromTheSiteIsDeliveredOnceAndLeavesTheAddressBar() {
        val back = get("/google?k=chiave&code=4%2F0Acodice%20uno")
        assertEquals(303, back.status)
        assertEquals("/?k=chiave#done", back.headers["location"])
        // Ricaricare la pagina di ritorno non consegna il codice due volte.
        assertEquals(303, get("/google?k=chiave&code=4%2F0Acodice%20uno").status)
        assertEquals(listOf("4/0Acodice uno"), codes)
        assertTrue(errors.isEmpty())
        assertEquals(400, get("/google?k=chiave").status)
        assertEquals(403, get("/google?k=altra&code=x").status)
    }

    @Test
    fun aRefusalOnThePhoneIsReportedAndThePickerAppearsWhenReady() {
        val back = get("/google?k=chiave&error=access_denied")
        assertEquals(303, back.status)
        assertEquals("/?k=chiave#cancelled", back.headers["location"])
        assertEquals(listOf("access_denied"), errors)
        assertEquals("{}", get("/status?k=chiave").body)
        server.pickerUrl = "https://photos.google.com/picker/s1"
        assertEquals("""{"picker":"https://photos.google.com/picker/s1"}""", get("/status?k=chiave").body)
    }

    @Test
    fun onlyGetIsAccepted() {
        val status = Socket(loopback, host.substringAfter(':').toInt()).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("POST /google?k=chiave HTTP/1.1\r\nHost: $host\r\nContent-Length: 0\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8).split(' ')[1].toInt()
        }
        assertEquals(405, status)
        assertTrue(codes.isEmpty())
    }
}
