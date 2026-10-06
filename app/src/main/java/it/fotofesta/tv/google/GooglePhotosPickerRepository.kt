package it.fotofesta.tv.google

import android.content.Context
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID

class GooglePhotosPickerRepository(
    private val context: Context,
    private val client: OkHttpClient,
) {
    data class PickingSession(
        val id: String,
        val pickerUri: String,
        val mediaItemsSet: Boolean,
        val pollIntervalSeconds: Long,
        val timeoutSeconds: Long,
    )

    data class PickedMedia(
        val id: String,
        val filename: String,
        val baseUrl: String,
        val mimeType: String,
    )

    fun newRequestId(): String = UUID.randomUUID().toString()

    suspend fun createSession(token: String, requestId: String): PickingSession = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://photospicker.googleapis.com/v1/sessions?requestId=$requestId")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        parseSession(executeJson(request))
    }

    suspend fun awaitSelection(token: String, initial: PickingSession): PickingSession {
        val started = System.currentTimeMillis()
        var current = initial
        while (!current.mediaItemsSet) {
            val timeoutMillis = current.timeoutSeconds.coerceAtLeast(60L) * 1_000L
            check(System.currentTimeMillis() - started < timeoutMillis) { "La selezione Google Foto è scaduta." }
            delay(current.pollIntervalSeconds.coerceIn(2L, 30L) * 1_000L)
            current = getSession(token, current.id)
        }
        return current
    }

    suspend fun listPickedMedia(token: String, sessionId: String): List<PickedMedia> = withContext(Dispatchers.IO) {
        val output = mutableListOf<PickedMedia>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("https://photospicker.googleapis.com/v1/mediaItems?sessionId=")
                append(sessionId)
                append("&pageSize=100")
                pageToken?.let { append("&pageToken=").append(it) }
            }
            val json = executeJson(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
            val items = json.optJSONArray("mediaItems")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val mediaFile = item.optJSONObject("mediaFile") ?: item
                    val mime = mediaFile.optString("mimeType")
                    if (mime.startsWith("image/")) {
                        output += PickedMedia(
                            id = item.optString("id", "photo-${output.size}"),
                            filename = mediaFile.optString("filename", "Foto ${output.size + 1}"),
                            baseUrl = mediaFile.getString("baseUrl"),
                            mimeType = mime,
                        )
                    }
                }
            }
            pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        output
    }

    suspend fun downloadToPrivateCache(
        token: String,
        items: List<PickedMedia>,
        onProgress: (Int, Int) -> Unit,
    ): List<PhotoItem> = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "google_photos").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val downloaded = mutableListOf<PhotoItem>()
        items.forEachIndexed { index, item ->
            val extension = if (item.mimeType == "image/png") "png" else "jpg"
            val safeId = item.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val target = File(directory, "$safeId.$extension")
            val request = Request.Builder()
                .url(item.baseUrl + "=w3840-h2160")
                .header("Authorization", "Bearer $token")
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Download non riuscito (${response.code})" }
                    response.body?.byteStream()?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Foto vuota")
                }
                downloaded += PhotoItem(
                    id = "google:${item.id}",
                    title = item.filename,
                    ref = PhotoRef.LocalFile(target.absolutePath),
                    source = SourceType.GOOGLE_PHOTOS,
                )
            }
            onProgress(index + 1, items.size)
        }
        downloaded
    }

    suspend fun deleteSession(token: String, sessionId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://photospicker.googleapis.com/v1/sessions/$sessionId")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        client.newCall(request).execute().close()
    }

    fun clearCache() {
        File(context.cacheDir, "google_photos").deleteRecursively()
    }

    fun cachedPhotos(): List<PhotoItem> =
        File(context.cacheDir, "google_photos")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.length() > 0L }
            .sortedBy { it.name.lowercase() }
            .map { file ->
                PhotoItem(
                    id = "google-cache:${file.nameWithoutExtension}",
                    title = file.name,
                    ref = PhotoRef.LocalFile(file.absolutePath),
                    source = SourceType.GOOGLE_PHOTOS,
                )
            }

    private fun getSession(token: String, id: String): PickingSession {
        val request = Request.Builder()
            .url("https://photospicker.googleapis.com/v1/sessions/$id")
            .header("Authorization", "Bearer $token")
            .build()
        return parseSession(executeJson(request))
    }

    private fun parseSession(json: JSONObject): PickingSession {
        val polling = json.optJSONObject("pollingConfig")
        return PickingSession(
            id = json.getString("id"),
            pickerUri = json.getString("pickerUri"),
            mediaItemsSet = json.optBoolean("mediaItemsSet", false),
            pollIntervalSeconds = durationSeconds(polling?.optString("pollInterval"), 5L),
            timeoutSeconds = durationSeconds(polling?.optString("timeoutIn"), 900L),
        )
    }

    private fun durationSeconds(value: String?, fallback: Long): Long =
        value?.removeSuffix("s")?.toDoubleOrNull()?.toLong() ?: fallback

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                error(message?.takeIf(String::isNotBlank) ?: "Google Foto non disponibile (${response.code}).")
            }
            return JSONObject(text.ifBlank { "{}" })
        }
    }
}
