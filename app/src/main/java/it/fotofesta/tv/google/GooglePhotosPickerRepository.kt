package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Fornisce un access token valido a ogni richiesta: durante una selezione lunga il token viene
 * rinnovato invece di scadere a metà.
 */
typealias TokenProvider = suspend () -> String

/**
 * Google Photos Picker API: sessioni di selezione ed elenco delle foto scelte. Le immagini non
 * vengono mai scaricate su disco: l'elenco fornisce indirizzi validi circa 60 minuti, che si
 * rinnovano chiedendo di nuovo l'elenco finché la sessione non scade.
 */
class GooglePhotosPickerRepository(
    private val client: OkHttpClient,
    private val apiBase: HttpUrl = DEFAULT_API_BASE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class PickingSession(
        val id: String,
        val pickerUri: String,
        val mediaItemsSet: Boolean,
        val pollIntervalSeconds: Long,
        /** Tempo residuo per scegliere le foto, come indicato da Google nell'ultima risposta. */
        val timeoutSeconds: Long?,
        /** Istante (epoch ms) dopo il quale la sessione e le sue foto non sono più accessibili. */
        val expiresAtMillis: Long?,
    )

    data class PickedMedia(
        val id: String,
        val filename: String,
        val baseUrl: String,
        val mimeType: String,
    ) {
        val isPhoto: Boolean get() = mimeType.startsWith("image/")
    }

    suspend fun createSession(token: String): PickingSession {
        val url = apiBase.newBuilder()
            .addPathSegment("sessions")
            .addQueryParameter("requestId", UUID.randomUUID().toString())
            .build()
        val request = authorized(url, token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return parseSession(client.fetchJson(request))
    }

    suspend fun awaitSelection(token: TokenProvider, initial: PickingSession): PickingSession {
        var current = initial
        var deadline = deadlineFor(current, fallbackSeconds = DEFAULT_SESSION_TIMEOUT_SECONDS)
        while (!current.mediaItemsSet) {
            if (clock() >= deadline) throw UserFacingException(R.string.error_google_selection_expired)
            delay(current.pollIntervalSeconds.coerceIn(2L, 30L) * 1_000L)
            current = getSession(token, current.id)
            // timeoutIn è relativo alla risposta: la scadenza va ricalcolata a ogni polling.
            current.timeoutSeconds?.let { deadline = deadlineFor(current, fallbackSeconds = it) }
        }
        return current
    }

    /** Tutti gli elementi scelti (foto e video), con indirizzi validi circa 60 minuti. */
    suspend fun listPickedMedia(token: TokenProvider, sessionId: String): List<PickedMedia> {
        val output = mutableListOf<PickedMedia>()
        var pageToken: String? = null
        do {
            val url = apiBase.newBuilder()
                .addPathSegment("mediaItems")
                .addQueryParameter("sessionId", sessionId)
                .addQueryParameter("pageSize", "100")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = client.fetchJson(authorized(url, token()).build())
            val items = json.optJSONArray("mediaItems")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val mediaFile = item.optJSONObject("mediaFile") ?: item
                    val baseUrl = mediaFile.optString("baseUrl")
                    val id = item.optString("id")
                    if (baseUrl.isBlank() || id.isBlank()) continue
                    output += PickedMedia(
                        id = id,
                        filename = mediaFile.optString("filename").ifBlank { "Foto ${output.size + 1}" },
                        baseUrl = baseUrl,
                        mimeType = mediaFile.optString("mimeType"),
                    )
                }
            }
            pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        return output
    }

    /** Elimina la sessione Picker lato Google. Best effort: la sessione scade comunque da sola. */
    suspend fun deleteSession(token: TokenProvider, sessionId: String) {
        val url = apiBase.newBuilder().addPathSegment("sessions").addPathSegment(sessionId).build()
        runCatching { client.newCall(authorized(url, token()).delete().build()).await().close() }
    }

    /** Stato attuale della sessione Picker. */
    suspend fun getSession(token: TokenProvider, id: String): PickingSession {
        val url = apiBase.newBuilder().addPathSegment("sessions").addPathSegment(id).build()
        return parseSession(client.fetchJson(authorized(url, token()).build()))
    }

    private fun authorized(url: HttpUrl, token: String) =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    private fun parseSession(json: JSONObject): PickingSession {
        val polling = json.optJSONObject("pollingConfig")
        return PickingSession(
            id = json.getString("id"),
            pickerUri = json.getString("pickerUri"),
            mediaItemsSet = json.optBoolean("mediaItemsSet", false),
            pollIntervalSeconds = parseProtoDurationSeconds(polling?.optString("pollInterval")) ?: 5L,
            timeoutSeconds = parseProtoDurationSeconds(polling?.optString("timeoutIn")),
            expiresAtMillis = parseTimestampMillis(json.optString("expireTime")),
        )
    }

    private fun deadlineFor(session: PickingSession, fallbackSeconds: Long): Long =
        clock() + (session.timeoutSeconds ?: fallbackSeconds).coerceAtLeast(60L) * 1_000L

    companion object {
        /** Cartelle delle foto scaricate dalle versioni fino alla 1.2.0, che copiavano le immagini sulla TV. */
        private const val LEGACY_DOWNLOAD_DIR = "google_photos"
        val DEFAULT_API_BASE = "https://photospicker.googleapis.com/v1".toHttpUrl()
        private const val DEFAULT_SESSION_TIMEOUT_SECONDS = 900L

        /** Elimina le copie delle foto Google lasciate dalle versioni precedenti, che le scaricavano. */
        fun deleteLegacyDownloads(vararg roots: File) {
            roots.forEach { root ->
                root.listFiles()
                    ?.filter { it.name == LEGACY_DOWNLOAD_DIR || it.name.startsWith("$LEGACY_DOWNLOAD_DIR.") }
                    ?.forEach { it.deleteRecursively() }
            }
        }
    }
}
