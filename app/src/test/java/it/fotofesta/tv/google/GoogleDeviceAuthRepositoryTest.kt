package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoogleDeviceAuthRepositoryTest {
    private val server = FakeGoogleServer()
    private val tokens = MemoryTokenStore()
    private val now = 1_800_000_000_000L

    private fun repository(clientId: String = "client", clientSecret: String = "secret") =
        GoogleDeviceAuthRepository(server.client, tokens, clientId, clientSecret, server.oauthBase) { now }

    @After
    fun tearDown() = server.close()

    @Test
    fun `device flow waits for authorization and stores tokens`() = runTest {
        server.enqueue(
            "POST", "/oauth/device/code",
            json("""{"device_code":"dev-1","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}"""),
        )
        server.enqueue(
            "POST", "/oauth/token",
            oauthError("authorization_pending", 428),
            oauthError("slow_down", 403),
            json("""{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3599}"""),
        )
        val auth = repository()
        val code = auth.requestDeviceCode()
        assertEquals("ABCD-EFGH", code.userCode)
        assertEquals("https://www.google.com/device", code.verificationUrl)

        val token = auth.pollForToken(code)

        assertEquals("access-1", token.accessToken)
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
        assertEquals(now / 1_000 + 3599, tokens.expiresAtEpochSeconds)
        val deviceRequest = server.requestsTo("POST", "/oauth/device/code").single().body.readUtf8()
        assertTrue(deviceRequest.contains("client_id=client"))
        assertTrue(deviceRequest.contains("photospicker.mediaitems.readonly"))
        val tokenRequests = server.requestsTo("POST", "/oauth/token")
        assertEquals(3, tokenRequests.size)
        assertTrue(tokenRequests.first().body.readUtf8().contains("device_code=dev-1"))
    }

    @Test
    fun `a dropped connection while waiting does not abort the sign in`() = runTest {
        server.enqueue(
            "POST", "/oauth/token",
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            json("""{"access_token":"after-drop","expires_in":3600}"""),
        )
        val code = GoogleDeviceAuthRepository.DeviceCode("dev", "CODE", "https://g.co/device", 600, 1)

        assertEquals("after-drop", repository().pollForToken(code).accessToken)
    }

    @Test
    fun `disconnect revokes the refresh token at Google`() = runTest {
        tokens.accessToken = "access"
        tokens.refreshToken = "refresh-1"
        server.always("POST", "/oauth/revoke", json("{}"))

        repository().revokeAndDisconnect()

        assertTrue(tokens.cleared)
        assertEquals("token=refresh-1", server.requestsTo("POST", "/oauth/revoke").single().body.readUtf8())
    }

    @Test
    fun `local tokens are cleared before the revocation request`() = runTest {
        tokens.refreshToken = "refresh-1"
        var clearedBeforeRequest = false
        server.onDispatch = { request -> if (request.requestUrl!!.encodedPath == "/oauth/revoke") clearedBeforeRequest = tokens.cleared }
        server.always("POST", "/oauth/revoke", json("{}"))

        repository().revokeAndDisconnect()

        assertTrue(clearedBeforeRequest)
    }

    @Test
    fun `local tokens are cleared even if revocation fails`() = runTest {
        tokens.refreshToken = "refresh-1"
        server.always("POST", "/oauth/revoke", json("""{"error":"invalid_token"}""", 400))

        repository().revokeAndDisconnect()

        assertTrue(tokens.cleared)
    }

    @Test
    fun `access denied on the phone becomes a user facing error`() = runTest {
        server.enqueue("POST", "/oauth/token", oauthError("access_denied", 403))
        val code = GoogleDeviceAuthRepository.DeviceCode("dev", "CODE", "https://g.co/device", 600, 1)
        try {
            repository().pollForToken(code)
            fail("expected UserFacingException")
        } catch (expected: UserFacingException) {
            assertEquals(R.string.error_google_access_denied, expected.messageRes)
        }
    }

    @Test
    fun `valid cached token needs no network`() = runTest {
        tokens.accessToken = "cached"
        tokens.expiresAtEpochSeconds = now / 1_000 + 600
        assertEquals("cached", repository().validAccessToken())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `expired token is refreshed keeping the refresh token`() = runTest {
        tokens.accessToken = "old"
        tokens.refreshToken = "refresh-1"
        tokens.expiresAtEpochSeconds = now / 1_000 + 30
        server.enqueue("POST", "/oauth/token", json("""{"access_token":"new","expires_in":3600}"""))

        assertEquals("new", repository().validAccessToken())

        assertEquals("refresh-1", tokens.refreshToken)
        assertTrue(server.requests.single().body.readUtf8().contains("grant_type=refresh_token"))
    }

    @Test
    fun `revoked refresh token clears storage and asks to connect again`() = runTest {
        tokens.refreshToken = "revoked"
        server.enqueue("POST", "/oauth/token", oauthError("invalid_grant"))

        assertNull(repository().validAccessToken())
        assertTrue(tokens.cleared)
    }

    @Test
    fun `server errors other than invalid grant are reported`() = runTest {
        tokens.refreshToken = "refresh"
        server.enqueue("POST", "/oauth/token", json("""{"error":{"message":"Backend error"}}""", 503))
        try {
            repository().validAccessToken()
            fail("expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(503, expected.code)
            assertEquals("Backend error", expected.serverMessage)
        }
        assertEquals("refresh", tokens.refreshToken)
    }

    @Test
    fun `missing credentials are reported before any request`() = runTest {
        val auth = repository(clientId = "", clientSecret = "")
        assertEquals(false, auth.isConfigured())
        try {
            auth.requestDeviceCode()
            fail("expected UserFacingException")
        } catch (expected: UserFacingException) {
            assertEquals(R.string.error_google_not_configured, expected.messageRes)
        }
        assertTrue(server.requests.isEmpty())
    }
}
