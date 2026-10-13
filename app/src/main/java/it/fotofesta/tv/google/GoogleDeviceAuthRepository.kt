package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * OAuth 2.0 per TV e dispositivi a input limitato. Credenziali, archivio dei token, endpoint e
 * orologio sono iniettati, così il flusso è verificabile nei test contro un server finto.
 */
class GoogleDeviceAuthRepository(
    private val client: OkHttpClient,
    private val tokens: TokenStore,
    private val clientId: String,
    private val clientSecret: String,
    oauthBase: HttpUrl = DEFAULT_OAUTH_BASE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresIn: Int,
        val interval: Int,
    )

    data class TokenResult(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Int,
    )

    private val deviceCodeUrl = oauthBase.newBuilder().addPathSegments("device/code").build()
    private val tokenUrl = oauthBase.newBuilder().addPathSegment("token").build()

    fun isConfigured(): Boolean = clientId.isNotBlank() && clientSecret.isNotBlank()

    suspend fun requestDeviceCode(): DeviceCode {
        requireConfigured()
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", SCOPE)
            .build()
        val json = client.fetchJson(
            Request.Builder().url(deviceCodeUrl).post(body).build(),
        )
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUrl = json.optString("verification_url").ifBlank { json.optString("verification_uri") },
            expiresIn = json.getInt("expires_in"),
            interval = json.optInt("interval", 5).coerceAtLeast(1),
        )
    }

    suspend fun pollForToken(code: DeviceCode): TokenResult {
        val deadline = clock() + code.expiresIn * 1_000L
        var intervalSeconds = code.interval
        while (clock() < deadline) {
            delay(intervalSeconds * 1_000L)
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val json = try {
                client.fetchJson(Request.Builder().url(tokenUrl).post(body).build())
            } catch (failure: HttpStatusException) {
                when (failure.oauthError) {
                    "authorization_pending" -> continue
                    "slow_down" -> {
                        intervalSeconds += 5
                        continue
                    }
                    "access_denied" -> throw UserFacingException(R.string.error_google_access_denied)
                    "expired_token" -> throw UserFacingException(R.string.error_google_code_expired)
                    else -> throw failure
                }
            }
            return persistToken(json)
        }
        throw UserFacingException(R.string.error_google_code_expired)
    }

    /**
     * Restituisce un access token valido, rinnovandolo se necessario. Se il refresh token è stato
     * revocato o è scaduto, cancella i token salvati e restituisce `null` per avviare un nuovo collegamento.
     */
    suspend fun validAccessToken(): String? {
        val now = clock() / 1_000L
        val cached = withContext(Dispatchers.IO) {
            tokens.accessToken?.takeIf { tokens.expiresAtEpochSeconds > now + 60 }
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
        val refresh = withContext(Dispatchers.IO) { tokens.refreshToken } ?: return null
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val json = client.fetchJson(Request.Builder().url(tokenUrl).post(body).build())
        return persistToken(json).accessToken
    }

    fun disconnect() = tokens.clear()

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
    }
}
