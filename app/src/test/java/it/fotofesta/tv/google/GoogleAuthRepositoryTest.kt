package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

class GoogleAuthRepositoryTest {
    private val server = FakeGoogleServer()
    private val tokens = MemoryTokenStore()
    private val now = 1_800_000_000_000L

    private fun repository(clientId: String = "client", clientSecret: String = "secret", redirectUri: String = REDIRECT) =
        GoogleAuthRepository(server.client, tokens, clientId, clientSecret, redirectUri, server.oauthBase) { now }

    @After
    fun tearDown() = server.close()

    @Test
    fun `authorization url carries pkce the scope and the way back to the tv`() {
        val auth = repository()
        val pending = auth.beginAuthorization()
        val url = auth.authorizationUrl(pending, "http://192.168.1.50:38211/google?k=abc").toHttpUrl()

        assertEquals("accounts.google.com", url.host)
        assertEquals("/o/oauth2/v2/auth", url.encodedPath)
        assertEquals("client", url.queryParameter("client_id"))
        assertEquals(REDIRECT, url.queryParameter("redirect_uri"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals(GoogleAuthRepository.SCOPE, url.queryParameter("scope"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        val digest = MessageDigest.getInstance("SHA-256").digest(pending.verifier.toByteArray())
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(digest), url.queryParameter("code_challenge"))
        assertEquals("http://192.168.1.50:38211/google?k=abc", GoogleAuthRepository.decodeState(url.queryParameter("state")!!))
        assertEquals("offline", url.queryParameter("access_type"))
        assertEquals("consent", url.queryParameter("prompt"))
        // Il segreto PKCE è lungo e diverso a ogni collegamento.
        assertTrue(pending.verifier.length >= 43)
        assertFalse(pending.verifier == auth.beginAuthorization().verifier)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `the code from the phone is exchanged with pkce and the tokens are stored`() = runTest {
        server.enqueue("POST", "/oauth/token", json("""{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3599}"""))
        val auth = repository()
        val pending = auth.beginAuthorization()

        val token = auth.exchangeCode(pending, "4/0Acode con spazi")

        assertEquals("access-1", token.accessToken)
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
        assertEquals(now / 1_000 + 3599, tokens.expiresAtEpochSeconds)
        val body = server.requestsTo("POST", "/oauth/token").single().body.readUtf8()
        assertTrue(body, body.contains("grant_type=authorization_code"))
        assertTrue(body, body.contains("code=4%2F0Acode%20con%20spazi"))
        assertTrue(body, body.contains("code_verifier=${pending.verifier}"))
        assertTrue(body, body.contains("redirect_uri=" + URLEncoder.encode(REDIRECT, "UTF-8")))
        assertTrue(body, body.contains("client_secret=secret"))
    }

    @Test
    fun `a used or expired code asks to start again`() = runTest {
        server.enqueue("POST", "/oauth/token", oauthError("invalid_grant"))
        val auth = repository()
        try {
            auth.exchangeCode(auth.beginAuthorization(), "vecchio")
            fail("expected UserFacingException")
        } catch (expected: UserFacingException) {
            assertEquals(R.string.error_google_code_expired, expected.messageRes)
        }
        assertNull(tokens.accessToken)
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
        assertFalse(repository(redirectUri = "").isConfigured())
        val auth = repository(clientId = "", clientSecret = "")
        assertFalse(auth.isConfigured())
        try {
            auth.beginAuthorization()
            fail("expected UserFacingException")
        } catch (expected: UserFacingException) {
            assertEquals(R.string.error_google_not_configured, expected.messageRes)
        }
        assertTrue(server.requests.isEmpty())
    }

    private companion object {
        const val REDIRECT = "https://esempio.github.io/fotofesta-tv/google/"
    }
}
