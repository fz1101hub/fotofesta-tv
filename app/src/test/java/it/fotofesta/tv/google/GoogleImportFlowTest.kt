package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoogleImportFlowTest {
    private val server = FakeGoogleServer()
    private val tokens = MemoryTokenStore()
    @Volatile
    private var now = 1_800_000_000_000L

    private fun flow() = GoogleImportFlow(
        GoogleDeviceAuthRepository(server.client, tokens, "client", "secret", server.oauthBase) { now },
        GooglePhotosPickerRepository(server.client, server.apiBase) { now },
    )

    @After
    fun tearDown() = server.close()

    private fun mediaItem(id: String, mime: String = "image/jpeg") =
        """{"id":"$id","mediaFile":{"baseUrl":"https://lh3.example/$id","mimeType":"$mime","filename":"$id.jpg"}}"""

    private fun pickerSessionReady() {
        server.enqueue("POST", "/v1/sessions", json("""{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":true,"expireTime":"2027-01-15T08:00:00Z"}"""))
        server.always("DELETE", "/v1/sessions/s1", json("{}"))
    }

    @Test
    fun `revoked token restarts authorization and keeps the session for streaming`() = runTest {
        tokens.accessToken = "revoked"
        tokens.expiresAtEpochSeconds = now / 1_000 + 3_000
        server.enqueue("POST", "/v1/sessions", json("""{"error":{"code":401,"message":"Invalid credentials"}}""", 401))
        server.enqueue("POST", "/oauth/device/code", json("""{"device_code":"d","user_code":"WXYZ","verification_url":"https://www.google.com/device","expires_in":600,"interval":1}"""))
        server.enqueue("POST", "/oauth/token", json("""{"access_token":"fresh","refresh_token":"r","expires_in":3600}"""))
        pickerSessionReady()
        server.enqueue("GET", "/v1/mediaItems", json("""{"mediaItems":[${mediaItem("p1")},${mediaItem("p2")}]}"""))
        val states = mutableListOf<GoogleConnectState>()

        val result = flow().run { synchronized(states) { states += it } }

        assertEquals(listOf("p1", "p2"), result.selection.items.map { it.id })
        assertEquals(listOf("p1.jpg", "p2.jpg"), result.selection.toPhotos().map { it.title })
        assertEquals("s1", result.selection.sessionId)
        assertEquals(java.time.Instant.parse("2027-01-15T08:00:00Z").toEpochMilli(), result.selection.expiresAtMillis)
        assertEquals(0, result.skipped)
        assertTrue(tokens.cleared)
        assertEquals("fresh", tokens.accessToken)
        assertEquals(GoogleConnectState.WaitingForAuthorization("https://www.google.com/device", "WXYZ", 600), states.first())
        assertEquals(GoogleConnectState.WaitingForPhotos("https://photos.google.com/picker/s1"), states.last())
        val sessionCreations = server.requestsTo("POST", "/v1/sessions")
        assertEquals(listOf("Bearer revoked", "Bearer fresh"), sessionCreations.map { it.getHeader("Authorization") })
        // La sessione resta aperta: serve a rinnovare gli indirizzi delle foto durante lo slideshow.
        assertTrue(server.requestsTo("DELETE", "/v1/sessions/s1").isEmpty())
        // Nessuna foto scaricata durante il collegamento.
        assertTrue(server.requestsTo("GET", "/p1").isEmpty())
    }

    @Test
    fun `empty selection is reported and the session is deleted`() = runTest {
        tokens.accessToken = "ok"
        tokens.expiresAtEpochSeconds = now / 1_000 + 3_000
        pickerSessionReady()
        server.enqueue("GET", "/v1/mediaItems", json("""{"mediaItems":[]}"""))

        try {
            flow().run { }
            fail("expected UserFacingException")
        } catch (expected: UserFacingException) {
            assertEquals(R.string.error_google_nothing_selected, expected.messageRes)
        }
        assertEquals(1, server.requestsTo("DELETE", "/v1/sessions/s1").size)
    }

    @Test
    fun `token expiring during the selection is refreshed for later requests`() = runTest {
        tokens.accessToken = "old"
        tokens.refreshToken = "refresh"
        tokens.expiresAtEpochSeconds = now / 1_000 + 90
        server.enqueue("POST", "/v1/sessions", json("""{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":false,"pollingConfig":{"pollInterval":"1s"}}"""))
        server.enqueue("GET", "/v1/sessions/s1", json("""{"id":"s1","pickerUri":"https://photos.google.com/picker/s1","mediaItemsSet":true}"""))
        // Mentre l'utente sceglie dal telefono passano dieci minuti: il token iniziale scade.
        server.onDispatch = { request ->
            if (request.method == "GET" && request.requestUrl!!.encodedPath == "/v1/sessions/s1") now += 600_000
        }
        server.always("DELETE", "/v1/sessions/s1", json("{}"))
        server.enqueue("POST", "/oauth/token", json("""{"access_token":"new","expires_in":3600}"""))
        server.enqueue("GET", "/v1/mediaItems", json("""{"mediaItems":[${mediaItem("p1")}]}"""))

        val result = flow().run { }

        assertEquals(1, result.selection.items.size)
        assertEquals("Bearer old", server.requestsTo("POST", "/v1/sessions").single().getHeader("Authorization"))
        assertEquals("Bearer new", server.requestsTo("GET", "/v1/mediaItems").single().getHeader("Authorization"))
    }

    @Test
    fun `videos are skipped and counted`() = runTest {
        tokens.accessToken = "ok"
        tokens.expiresAtEpochSeconds = now / 1_000 + 3_000
        pickerSessionReady()
        server.enqueue("GET", "/v1/mediaItems", json("""{"mediaItems":[${mediaItem("ok")},${mediaItem("film", "video/mp4")}]}"""))

        val result = flow().run { }

        assertEquals(listOf("ok"), result.selection.items.map { it.id })
        assertEquals(1, result.skipped)
    }

    @Test
    fun `only videos are reported as nothing selected`() = runTest {
        tokens.accessToken = "ok"
        tokens.expiresAtEpochSeconds = now / 1_000 + 3_000
        pickerSessionReady()
        server.enqueue("GET", "/v1/mediaItems", json("""{"mediaItems":[${mediaItem("film", "video/mp4")}]}"""))

        try {
            flow().run { }
            fail("expected UserFacingException")
        } catch (expected: UserFacingException) {
            assertEquals(R.string.error_google_nothing_selected, expected.messageRes)
        }
        assertEquals(1, server.requestsTo("DELETE", "/v1/sessions/s1").size)
    }
}
