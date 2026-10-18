package it.fotofesta.tv.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class LanHttpTest {
    private val http = LanHttp(allowLoopback = true)

    @Test
    fun hostHeaderIsTheAddressAsWritten() {
        assertEquals("nas.local", LanHttp.hostHeader("nas.local", 80))
        assertEquals("nas.local:8200", LanHttp.hostHeader("nas.local", 8200))
        assertEquals("192.168.1.20:8200", LanHttp.hostHeader("192.168.1.20", 8200))
        assertEquals("[fe80::1]:8200", LanHttp.hostHeader("[fe80::1]", 8200))
    }

    @Test
    fun onlyHomeNetworkAddressesAreAccepted() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.3.4", "172.31.255.1", "169.254.10.10", "fd12:3456::1", "fe80::1").forEach {
            assertTrue(it, LanHttp.isLanAddress(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "127.0.0.1", "2001:4860::8888", "::1").forEach {
            assertFalse(it, LanHttp.isLanAddress(InetAddress.getByName(it)))
        }
    }

    @Test
    fun internetAndNonHttpAddressesAreRefused() {
        val strict = LanHttp()
        listOf("http://8.8.8.8/foto.jpg", "https://192.168.1.2/foto.jpg", "http://127.0.0.1:9/x", "file:///etc/passwd", "http://user@192.168.1.2/").forEach {
            assertFalse(it, strict.accepts(it))
            try {
                strict.get(it).close()
                fail("Doveva rifiutare $it")
            } catch (_: NotLanAddressException) {
            }
        }
        assertTrue(strict.accepts("http://192.168.1.2:8200/MediaItems/1.jpg"))
    }

    @Test
    fun readsFixedLengthAndChunkedBodies() {
        val photo = ByteArray(1_000) { (it % 251).toByte() }
        FakeLanServer { request ->
            when (request.path) {
                "/fissa" -> FakeResponse(body = photo, headers = mapOf("Content-Type" to "image/jpeg"))
                else -> FakeResponse(body = photo, chunked = true)
            }
        }.use { server ->
            http.get("${server.base}/fissa").use { response ->
                assertEquals(200, response.status)
                assertEquals("image/jpeg", response.headers["content-type"])
                assertArrayEquals(photo, response.bytes(10_000))
            }
            http.get("${server.base}/blocchi").use { assertArrayEquals(photo, it.bytes(10_000)) }
            assertEquals("127.0.0.1:${server.base.substringAfterLast(':')}", server.requests.first().headers["host"])
        }
    }

    @Test
    fun followsRedirectsOnlyInsideTheHomeNetwork() {
        FakeLanServer { request ->
            when (request.path) {
                "/vecchia" -> FakeResponse(302, headers = mapOf("Location" to "/nuova"))
                "/nuova" -> FakeResponse(body = "ok".toByteArray())
                else -> FakeResponse(301, headers = mapOf("Location" to "http://8.8.8.8/fuori"))
            }
        }.use { server ->
            http.get("${server.base}/vecchia").use { assertEquals("ok", String(it.bytes(100))) }
            try {
                http.get("${server.base}/fuori").close()
                fail("Il reindirizzamento verso Internet va rifiutato")
            } catch (_: NotLanAddressException) {
            }
        }
    }

    @Test
    fun postsTheBodyWithItsLength() {
        FakeLanServer { request -> FakeResponse(body = (request.method + " " + request.text + " " + request.headers["soapaction"]).toByteArray()) }.use { server ->
            http.post("${server.base}/ctl", mapOf("SOAPAction" to "\"azione\""), "corpo".toByteArray()).use {
                assertEquals("POST corpo \"azione\"", String(it.bytes(100)))
            }
        }
    }

    @Test
    fun tooLargeBodiesAreRefused() {
        FakeLanServer { FakeResponse(body = ByteArray(2_000)) }.use { server ->
            http.get(server.base).use {
                try {
                    it.bytes(1_000)
                    fail("Corpo oltre il limite")
                } catch (_: IOException) {
                }
            }
        }
    }
}
