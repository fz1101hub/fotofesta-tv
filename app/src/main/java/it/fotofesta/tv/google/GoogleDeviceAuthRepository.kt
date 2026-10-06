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

    fun isConfigured(): Boolean =
        BuildConfig.PHOTOS_CLIENT_ID.isNotBlank() && BuildConfig.PHOTOS_CLIENT_SECRET.isNotBlank()

    suspend fun requestDeviceCode(requestId: String): DeviceCode = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.PHOTOS_CLIENT_ID)
            .add("scope", scope)
            .add("state", requestId)
            .build()
        val json = executeJson(
            Request.Builder()
                .url("https://oauth2.googleapis.com/device/code")
                .post(body)
                .build()
        )
        DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUrl = json.optString("verification_url", json.optString("verification_uri")),
            expiresIn = json.getInt("expires_in"),
            interval = json.optInt("interval", 5).coerceAtLeast(1),
        )
    }

    suspend fun pollForToken(code: DeviceCode): TokenResult = withContext(Dispatchers.IO) {
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
            val request = Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = JSONObject(text.ifBlank { "{}" })
                if (response.isSuccessful) {
                    return@withContext persistToken(json)
                }
                when (json.optString("error")) {
                    "authorization_pending" -> Unit
                    "slow_down" -> intervalSeconds += 5
                    "access_denied" -> error("Autorizzazione annullata sul telefono.")
                    "expired_token" -> error("Il codice Google è scaduto. Riprova.")
                    else -> error(json.optString("error_description", "Autorizzazione Google non riuscita."))
                }
            }
        }
        error("Il codice Google è scaduto. Riprova.")
    }

    suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1_000L
        tokens.accessToken?.takeIf { tokens.expiresAtEpochSeconds > now + 60 } ?: refreshAccessToken()
    }

    suspend fun refreshAccessToken(): String? = withContext(Dispatchers.IO) {
        requireConfigured()
        val refresh = tokens.refreshToken ?: return@withContext null
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.PHOTOS_CLIENT_ID)
            .add("client_secret", BuildConfig.PHOTOS_CLIENT_SECRET)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val json = executeJson(Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build())
        persistToken(json).accessToken
    }

    fun disconnect() = tokens.clear()

    private fun persistToken(json: JSONObject): TokenResult {
        val result = TokenResult(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf(String::isNotBlank),
            expiresIn = json.optInt("expires_in", 3_600),
        )
        tokens.accessToken = result.accessToken
        result.refreshToken?.let { tokens.refreshToken = it }
        tokens.expiresAtEpochSeconds = System.currentTimeMillis() / 1_000L + result.expiresIn
        return result
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                error(message?.takeIf(String::isNotBlank) ?: "Errore di rete Google (${response.code}).")
            }
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    private fun requireConfigured() {
        check(isConfigured()) {
            "Configura PHOTOS_CLIENT_ID e PHOTOS_CLIENT_SECRET nel file local.properties."
        }
    }
}
