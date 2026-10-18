package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * OAuth 2.0 con il consenso dato dal telefono: il flusso per TV di Google non ammette lo scope del
 * Photos Picker, quindi la TV mostra un QR con una sua pagina, il telefono va da Google (flusso
 * "authorization code" con PKCE) e torna, attraverso la pagina del sito indicata come
 * `redirect_uri`, con un codice che la TV scambia con i token. Credenziali, archivio dei token,
 * endpoint e orologio sono iniettati, così il flusso è verificabile nei test contro un server finto.
 */
class GoogleAuthRepository(
    private val client: OkHttpClient,
    private val tokens: TokenStore,
    private val clientId: String,
    private val clientSecret: String,
    /** Pagina del sito che riceve il codice da Google e lo rimanda alla TV (`site/google/`). */
    private val redirectUri: String,
    oauthBase: HttpUrl = DEFAULT_OAUTH_BASE,
    private val authorizationUrl: HttpUrl = DEFAULT_AUTHORIZATION_URL,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Un'autorizzazione iniziata: il segreto PKCE resta sulla TV, al telefono va solo la sua impronta. */
    class Pending(val verifier: String, val challenge: String)

    data class TokenResult(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Int,
    )

    private val tokenUrl = oauthBase.newBuilder().addPathSegment("token").build()
    private val revokeUrl = oauthBase.newBuilder().addPathSegment("revoke").build()

    fun isConfigured(): Boolean = clientId.isNotBlank() && clientSecret.isNotBlank() && redirectUri.isNotBlank()

    fun beginAuthorization(): Pending {
        requireConfigured()
        val bytes = ByteArray(32).also(random::nextBytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pending(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
    }

    /**
     * Indirizzo di Google da aprire sul telefono. [returnUrl] è la pagina della TV a cui il sito
     * rimanda il codice: viaggia nello `state`, che Google restituisce intatto.
     */
    fun authorizationUrl(pending: Pending, returnUrl: String): String =
        authorizationUrl.newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("code_challenge", pending.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", encodeState(returnUrl))
            // Refresh token a ogni collegamento, anche se l'utente aveva già dato il consenso.
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .build()
            .toString()

    /** Scambia il codice arrivato dal telefono con i token e li salva. */
    suspend fun exchangeCode(pending: Pending, code: String): TokenResult {
        requireConfigured()
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code", code)
            .add("code_verifier", pending.verifier)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .build()
        val json = try {
            client.fetchJson(Request.Builder().url(tokenUrl).post(body).build())
        } catch (failure: HttpStatusException) {
            // Codice già usato o scaduto (vale pochi minuti): si ricomincia dal QR.
            if (failure.oauthError == "invalid_grant") throw UserFacingException(R.string.error_google_code_expired)
            throw failure
        }
        return persistToken(json)
    }

    /**
     * Restituisce un access token valido, rinnovandolo se necessario. Se il refresh token è stato
     * revocato o è scaduto, cancella i token salvati e restituisce `null` per avviare un nuovo collegamento.
     * Con [forceRefresh] il token viene rinnovato anche se non è scaduto (ad esempio dopo un 401).
     */
    suspend fun validAccessToken(forceRefresh: Boolean = false): String? {
        val now = clock() / 1_000L
        val cached = withContext(Dispatchers.IO) {
            tokens.accessToken?.takeIf { !forceRefresh && tokens.expiresAtEpochSeconds > now + 60 }
        }
        if (cached != null) return cached
        return try {
            refreshAccessToken()
        } catch (failure: HttpStatusException) {
            if (failure.oauthError == "invalid_grant" || failure.code == 401) {
                disconnect()
                null
            } else {
                throw failure
            }
        }
    }

    private suspend fun refreshAccessToken(): String? {
        requireConfigured()
        // Un "Disconnetti" arrivato durante la richiesta invalida il risultato: il token non va salvato.
        val generation = this.generation
        val refresh = withContext(Dispatchers.IO) { tokens.refreshToken } ?: return null
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val json = client.fetchJson(Request.Builder().url(tokenUrl).post(body).build())
        if (generation != this.generation) return null
        return persistToken(json).accessToken
    }

    /** Cresce a ogni disconnessione: i rinnovi partiti prima non salvano il token ottenuto. */
    @Volatile
    private var generation = 0

    fun disconnect() {
        generation++
        tokens.clear()
    }

    /**
     * Revoca l'accesso anche sui server Google (best effort) e cancella i token locali.
     * Senza revoca il refresh token resterebbe valido finché l'utente non lo rimuove dal suo account.
     */
    suspend fun revokeAndDisconnect() {
        // Prima si cancellano i token locali (subito, anche se la rete è lenta), poi si revoca.
        val token = withContext(Dispatchers.IO) {
            (tokens.refreshToken ?: tokens.accessToken).also { disconnect() }
        }
        if (token != null) {
            val body = FormBody.Builder().add("token", token).build()
            try {
                client.newCall(Request.Builder().url(revokeUrl).post(body).build()).await().close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best effort: l'utente può sempre revocare dall'Account Google.
            }
        }
    }

    private suspend fun persistToken(json: JSONObject): TokenResult = withContext(Dispatchers.IO) {
        val result = TokenResult(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf(String::isNotBlank),
            expiresIn = json.optInt("expires_in", 3_600),
        )
        tokens.accessToken = result.accessToken
        result.refreshToken?.let { tokens.refreshToken = it }
        tokens.expiresAtEpochSeconds = clock() / 1_000L + result.expiresIn
        result
    }

    private fun requireConfigured() {
        if (!isConfigured()) throw UserFacingException(R.string.error_google_not_configured)
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
        val DEFAULT_OAUTH_BASE = "https://oauth2.googleapis.com/".toHttpUrl()
        val DEFAULT_AUTHORIZATION_URL = "https://accounts.google.com/o/oauth2/v2/auth".toHttpUrl()

        /** Lo `state` porta l'indirizzo di ritorno sulla TV, in base64url come lo legge la pagina del sito. */
        fun encodeState(returnUrl: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(returnUrl.toByteArray(Charsets.UTF_8))

        fun decodeState(state: String): String? =
            runCatching { String(Base64.getUrlDecoder().decode(state), Charsets.UTF_8) }.getOrNull()
    }
}
