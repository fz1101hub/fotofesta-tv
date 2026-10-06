package it.fotofesta.tv.google

import android.content.Context
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
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
        /** Tempo residuo prima che la sessione scada, come indicato da Google nell'ultima risposta. */
        val timeoutSeconds: Long?,
    )

    data class PickedMedia(
        val id: String,
        val filename: String,
        val baseUrl: String,
        val mimeType: String,
    )

    private val apiBase = "https://photospicker.googleapis.com/v1".toHttpUrl()
    private val cacheDirectory get() = File(context.cacheDir, CACHE_DIR)

    suspend fun createSession(token: String): PickingSession {
        val url = apiBase.newBuilder()
            .addPathSegment("sessions")
            .addQueryParameter("requestId", UUID.randomUUID().toString())
            .build()
        val request = authorized(url, token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return parseSession(client.fetchJson(request, ::serviceError))
    }

    suspend fun awaitSelection(token: String, initial: PickingSession): PickingSession {
        var current = initial
        var deadline = deadlineFor(current, fallbackSeconds = DEFAULT_SESSION_TIMEOUT_SECONDS)
        while (!current.mediaItemsSet) {
            check(System.currentTimeMillis() < deadline) { "La selezione Google Foto è scaduta." }
            delay(current.pollIntervalSeconds.coerceIn(2L, 30L) * 1_000L)
            current = getSession(token, current.id)
            // timeoutIn è relativo alla risposta: la scadenza va ricalcolata a ogni polling.
            current.timeoutSeconds?.let { deadline = deadlineFor(current, fallbackSeconds = it) }
        }
        return current
    }

    suspend fun listPickedMedia(token: String, sessionId: String): List<PickedMedia> {
        val output = mutableListOf<PickedMedia>()
        var pageToken: String? = null
        do {
            val url = apiBase.newBuilder()
                .addPathSegment("mediaItems")
                .addQueryParameter("sessionId", sessionId)
                .addQueryParameter("pageSize", "100")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = client.fetchJson(authorized(url, token).build(), ::serviceError)
            val items = json.optJSONArray("mediaItems")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val mediaFile = item.optJSONObject("mediaFile") ?: item
                    val mime = mediaFile.optString("mimeType")
                    val baseUrl = mediaFile.optString("baseUrl")
                    if (mime.startsWith("image/") && baseUrl.isNotBlank()) {
                        output += PickedMedia(
                            id = item.optString("id").ifBlank { "photo-${output.size}" },
                            filename = mediaFile.optString("filename").ifBlank { "Foto ${output.size + 1}" },
                            baseUrl = baseUrl,
                            mimeType = mime,
                        )
                    }
                }
            }
            pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        return output
    }

    /**
     * Scarica le foto in una cartella temporanea e, solo a download concluso, la sostituisce alla
     * cache precedente. In caso di errore o annullamento la raccolta già in uso resta intatta.
     * Si ferma prima di esaurire lo spazio del dispositivo.
     */
    suspend fun downloadToPrivateCache(
        token: String,
        items: List<PickedMedia>,
        onProgress: (Int, Int) -> Unit,
    ): List<PhotoItem> = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "$CACHE_DIR.staging-${UUID.randomUUID()}").apply { mkdirs() }
        var committed = false
        try {
            var completed = 0
            val progressLock = Any()
            val permits = Semaphore(PARALLEL_DOWNLOADS)
            val downloaded = coroutineScope {
                items.mapIndexed { index, item ->
                    async {
                        permits.withPermit {
                            val fileName = if (staging.usableSpace > MIN_FREE_BYTES) {
                                downloadOne(token, item, index, staging)
                            } else null
                            synchronized(progressLock) { onProgress(++completed, items.size) }
                            fileName?.let { item to it }
                        }
                    }
                }.awaitAll()
            }.filterNotNull()
            if (downloaded.isEmpty()) return@withContext emptyList()
            val photos = downloaded.map { (item, fileName) ->
                PhotoItem(
                    id = "google:${item.id}",
                    title = item.filename,
                    ref = PhotoRef.LocalFile(File(cacheDirectory, fileName).absolutePath),
                    source = SourceType.GOOGLE_PHOTOS,
                )
            }
            writeManifest(staging, photos)
            cacheDirectory.deleteRecursively()
            check(staging.renameTo(cacheDirectory)) { "Impossibile salvare le foto nella cache." }
            committed = true
            photos
        } finally {
            if (!committed) staging.deleteRecursively()
        }
    }

    /** Scarica una foto in [directory] e restituisce il nome del file, oppure `null` se non disponibile. */
    private suspend fun downloadOne(token: String, item: PickedMedia, index: Int, directory: File): String? {
        val extension = when (item.mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val target = File(directory, "%05d.%s".format(Locale.ROOT, index, extension))
        val request = Request.Builder()
            .url(item.baseUrl + "=w3840-h2160")
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            client.newCall(request).await().use { response ->
                check(response.isSuccessful) { "Download non riuscito (${response.code})" }
                val body = response.body ?: error("Foto vuota")
                target.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
            check(target.length() > 0L) { "Foto vuota" }
            target.name
        } catch (cancelled: CancellationException) {
            target.delete()
            throw cancelled
        } catch (_: Exception) {
            // Una foto non scaricabile non deve bloccare le altre né restare parziale in cache.
            target.delete()
            null
        }
    }

    /** Elimina la sessione Picker lato Google. Best effort: un errore qui non invalida le foto scaricate. */
    suspend fun deleteSession(token: String, sessionId: String) {
        val url = apiBase.newBuilder().addPathSegment("sessions").addPathSegment(sessionId).build()
        runCatching { client.newCall(authorized(url, token).delete().build()).await().close() }
    }

    fun clearCache() {
        context.cacheDir.listFiles()
            ?.filter { it.name == CACHE_DIR || it.name.startsWith("$CACHE_DIR.staging-") }
            ?.forEach { it.deleteRecursively() }
    }

    /** Foto della cache privata, nell'ordine e con i titoli originali salvati nel manifest. */
    fun cachedPhotos(): List<PhotoItem> {
        val directory = cacheDirectory
        val fromManifest = runCatching {
            val array = JSONArray(File(directory, MANIFEST).readText())
            (0 until array.length()).mapNotNull { index ->
                val entry = array.getJSONObject(index)
                val file = File(directory, entry.getString("file"))
                if (!file.isFile || file.length() == 0L || file.parentFile != directory) return@mapNotNull null
                PhotoItem(
                    id = entry.optString("id").ifBlank { "google-cache:${file.name}" },
                    title = entry.optString("title").ifBlank { file.name },
                    ref = PhotoRef.LocalFile(file.absolutePath),
                    source = SourceType.GOOGLE_PHOTOS,
                )
            }
        }.getOrNull()
        if (fromManifest != null) return fromManifest
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.length() > 0L && it.name != MANIFEST }
            .sortedBy { it.name.lowercase() }
            .map { file ->
                PhotoItem(
                    id = "google-cache:${file.nameWithoutExtension}",
                    title = file.name,
                    ref = PhotoRef.LocalFile(file.absolutePath),
                    source = SourceType.GOOGLE_PHOTOS,
                )
            }
    }

    private fun writeManifest(directory: File, photos: List<PhotoItem>) {
        val array = JSONArray()
        photos.forEach { photo ->
            array.put(
                JSONObject()
                    .put("id", photo.id)
                    .put("title", photo.title)
                    .put("file", File((photo.ref as PhotoRef.LocalFile).path).name)
            )
        }
        File(directory, MANIFEST).writeText(array.toString())
    }

    private suspend fun getSession(token: String, id: String): PickingSession {
        val url = apiBase.newBuilder().addPathSegment("sessions").addPathSegment(id).build()
        return parseSession(client.fetchJson(authorized(url, token).build(), ::serviceError))
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
        )
    }

    private fun deadlineFor(session: PickingSession, fallbackSeconds: Long): Long =
        System.currentTimeMillis() + (session.timeoutSeconds ?: fallbackSeconds).coerceAtLeast(60L) * 1_000L

    private fun serviceError(code: Int) = "Google Foto non disponibile ($code)."

    companion object {
        private const val CACHE_DIR = "google_photos"
        private const val MANIFEST = "index.json"
        private const val PARALLEL_DOWNLOADS = 4
        private const val MIN_FREE_BYTES = 300L * 1024 * 1024
        private const val DEFAULT_SESSION_TIMEOUT_SECONDS = 900L
    }
}
