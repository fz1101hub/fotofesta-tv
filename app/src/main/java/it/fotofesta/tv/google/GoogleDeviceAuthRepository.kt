package it.fotofesta.tv.google

import it.fotofesta.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class GoogleDeviceAuthRepository(
    private val client: OkHttpClient,
    private val tokens: SecureTokenStore,
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

    private val scope = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
    private val tokenUrl = "https://oauth2.googleapis.com/token"

    fun isConfigured(): Boolean =
        BuildConfig.PHOTOS_CLIENT_ID.isNotBlank() && BuildConfig.PHOTOS_CLIENT_SECRET.isNotBlank()

    suspend fun requestDeviceCode(): DeviceCode {
        requireConfigured()
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.PHOTOS_CLIENT_ID)
            .add("scope", scope)
            .build()
        val json = client.fetchJson(
            Request.Builder().url("https://oauth2.googleapis.com/device/code").post(body).build(),
            ::networkError,
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
        val deadline = System.currentTimeMillis() + code.expiresIn * 1_000L
        var intervalSeconds = code.interval
        while (System.currentTimeMillis() < deadline) {
            delay(intervalSeconds * 1_000L)
            val body = FormBody.Builder()
                .add("client_id", BuildConfig.PHOTOS_CLIENT_ID)
                .add("client_secret", BuildConfig.PHOTOS_CLIENT_SECRET)
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val json = try {
                client.fetchJson(Request.Builder().url(tokenUrl).post(body).build(), ::networkError)
            } catch (failure: HttpStatusException) {
                when (failure.oauthError) {
                    "authorization_pending" -> continue
                    "slow_down" -> {
                        intervalSeconds += 5
                        continue
                    }
                    "access_denied" -> error("Autorizzazione annullata sul telefono.")
                    "expired_token" -> error("Il codice Google è scaduto. Riprova.")
                    else -> throw failure
                }
            }
            return persistToken(json)
        }
        error("Il codice Google è scaduto. Riprova.")
    }

    /**
     * Restituisce un access token valido, rinnovandolo se necessario. Se il refresh token è stato
     * revocato o è scaduto, cancella i token salvati e restituisce `null` per avviare un nuovo collegamento.
     */
    suspend fun validAccessToken(): String? {
        val now = System.currentTimeMillis() / 1_000L
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
            .add("client_id", BuildConfig.PHOTOS_CLIENT_ID)
            .add("client_secret", BuildConfig.PHOTOS_CLIENT_SECRET)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val json = client.fetchJson(Request.Builder().url(tokenUrl).post(body).build(), ::networkError)
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
        tokens.expiresAtEpochSeconds = System.currentTimeMillis() / 1_000L + result.expiresIn
        result
    }

    private fun networkError(code: Int) = "Errore di rete Google ($code)."

    private fun requireConfigured() {
        check(isConfigured()) {
            "Configura PHOTOS_CLIENT_ID e PHOTOS_CLIENT_SECRET nel file local.properties."
        }
    }
}
