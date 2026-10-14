package it.fotofesta.tv.google

import it.fotofesta.tv.R
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Fornisce un access token valido a ogni richiesta: durante una selezione lunga o un download
 * di centinaia di foto il token viene rinnovato invece di scadere a metà.
 */
typealias TokenProvider = suspend () -> String

/**
 * Google Photos Picker API e cache privata delle foto scelte. [cacheRoot] è la cartella cache
 * dell'app; endpoint e soglia di spazio libero sono iniettabili per i test.
 */
class GooglePhotosPickerRepository(
    private val cacheRoot: File,
    private val client: OkHttpClient,
    private val apiBase: HttpUrl = DEFAULT_API_BASE,
    private val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
    private val renameDirectory: (File, File) -> Boolean = File::renameTo,
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

    private val cacheDirectory get() = File(cacheRoot, CACHE_DIR)

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
            if (System.currentTimeMillis() >= deadline) throw UserFacingException(R.string.error_google_selection_expired)
            delay(current.pollIntervalSeconds.coerceIn(2L, 30L) * 1_000L)
            current = getSession(token, current.id)
            // timeoutIn è relativo alla risposta: la scadenza va ricalcolata a ogni polling.
            current.timeoutSeconds?.let { deadline = deadlineFor(current, fallbackSeconds = it) }
        }
        return current
    }

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
        token: TokenProvider,
        items: List<PickedMedia>,
        onProgress: (Int, Int) -> Unit,
    ): List<PhotoItem> = withContext(Dispatchers.IO) {
        val staging = File(cacheRoot, "$CACHE_DIR.staging-${UUID.randomUUID()}").apply { mkdirs() }
        var committed = false
        try {
            var completed = 0
            val progressLock = Any()
            val permits = Semaphore(PARALLEL_DOWNLOADS)
            val downloaded = coroutineScope {
                items.mapIndexed { index, item ->
                    async {
                        permits.withPermit {
                            val fileName = if (staging.usableSpace > minFreeBytes) {
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
            // La cache precedente viene spostata da parte e cancellata solo dopo lo scambio riuscito.
            val previous = File(cacheRoot, "$CACHE_DIR.old-${UUID.randomUUID()}")
            val hadPrevious = cacheDirectory.exists()
            if (hadPrevious && !renameDirectory(cacheDirectory, previous)) throw UserFacingException(R.string.error_cache_write)
            if (!renameDirectory(staging, cacheDirectory)) {
                // Non cancellare il backup se fallisce anche il rollback: sara recuperato al riavvio.
                if (hadPrevious) renameDirectory(previous, cacheDirectory)
                throw UserFacingException(R.string.error_cache_write)
            }
            committed = true
            previous.deleteRecursively()
            photos
        } finally {
            if (!committed) staging.deleteRecursively()
        }
    }

    /** Scarica una foto in [directory] e restituisce il nome del file, oppure `null` se non disponibile. */
    private suspend fun downloadOne(token: TokenProvider, item: PickedMedia, index: Int, directory: File): String? {
        val extension = when (item.mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val target = File(directory, "%05d.%s".format(Locale.ROOT, index, extension))
        return try {
            val request = Request.Builder()
                .url(item.baseUrl + "=w3840-h2160")
                .header("Authorization", "Bearer ${token()}")
                .build()
            client.newCall(request).await().use { response ->
                check(response.isSuccessful) { "Download non riuscito (${response.code})" }
                val body = response.body ?: error("Foto vuota")
                val mime = response.header("Content-Type")?.substringBefore(';')?.trim()
                if (mime != null && !mime.startsWith("image/")) throw IOException("Formato non valido")
                if (body.contentLength() > MAX_IMAGE_BYTES) throw IOException("Foto troppo grande")
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMAGE_BYTES || directory.usableSpace <= minFreeBytes) {
                                throw IOException("Dimensione o spazio insufficiente")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
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
    suspend fun deleteSession(token: TokenProvider, sessionId: String) {
        val url = apiBase.newBuilder().addPathSegment("sessions").addPathSegment(sessionId).build()
        runCatching { client.newCall(authorized(url, token()).delete().build()).await().close() }
    }

    /**
     * Sposta qui la raccolta conservata da [legacy] (es. la cache delle versioni precedenti) se qui
     * non ce n'è ancora una, poi ripulisce [legacy].
     */
    fun adoptFrom(legacy: GooglePhotosPickerRepository) {
        val source = legacy.cacheDirectory
        if (!source.isDirectory) return
        if (!cacheDirectory.exists()) {
            cacheRoot.mkdirs()
            // Se la rinomina fallisce, preserva i file precedenti.
            if (renameDirectory(source, cacheDirectory) && cacheDirectory.isDirectory) {
                legacy.clearCache()
            }
        } else if (cacheDirectory.isDirectory && cachedPhotos().isNotEmpty()) {
            legacy.clearCache()
        }
    }

    /** Ripristina il backup se un crash ha interrotto lo scambio e pulisce solo copie non necessarie. */
    fun deleteLeftovers() {
        val entries = cacheRoot.listFiles().orEmpty()
        val backups = entries.filter { it.isDirectory && it.name.startsWith("$CACHE_DIR.old-") }
            .sortedByDescending { it.lastModified() }
        var activeValid = cacheDirectory.isDirectory && cachedPhotos().isNotEmpty()
        if (!activeValid) {
            val previous = backups.firstOrNull { backup ->
                File(backup, MANIFEST).isFile &&
                    backup.listFiles().orEmpty().any { it.isFile && it.name != MANIFEST && it.length() > 0L }
            }
            if (previous != null) {
                val displaced = File(cacheRoot, "$CACHE_DIR.corrupt-${UUID.randomUUID()}")
                val existed = cacheDirectory.exists()
                if (!existed || renameDirectory(cacheDirectory, displaced)) {
                    if (renameDirectory(previous, cacheDirectory) && cachedPhotos().isNotEmpty()) {
                        activeValid = true
                        displaced.deleteRecursively()
                    } else if (existed && !cacheDirectory.exists()) {
                        renameDirectory(displaced, cacheDirectory)
                    }
                }
            }
        }
        entries.filter { it.name.startsWith("$CACHE_DIR.staging-") }
            .forEach { it.deleteRecursively() }
        // Mai cancellare l'unica copia valida.
        if (activeValid) backups.filter { it.exists() }.forEach { it.deleteRecursively() }
    }

    fun clearCache() {
        cacheRoot.listFiles()
            ?.filter { it.name == CACHE_DIR || it.name.startsWith("$CACHE_DIR.") }
            ?.forEach { it.deleteRecursively() }
    }

    /** Foto della cache privata, nell'ordine e con i titoli originali salvati nel manifest. */
    fun cachedPhotos(): List<PhotoItem> = cachedPhotos(cacheRoot)

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

    private suspend fun getSession(token: TokenProvider, id: String): PickingSession {
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
        )
    }

    private fun deadlineFor(session: PickingSession, fallbackSeconds: Long): Long =
        System.currentTimeMillis() + (session.timeoutSeconds ?: fallbackSeconds).coerceAtLeast(60L) * 1_000L

    companion object {
        private const val CACHE_DIR = "google_photos"
        private const val MANIFEST = "index.json"
        private const val PARALLEL_DOWNLOADS = 4
        private const val MAX_IMAGE_BYTES = 24L * 1024 * 1024
        const val DEFAULT_MIN_FREE_BYTES = 300L * 1024 * 1024
        val DEFAULT_API_BASE = "https://photospicker.googleapis.com/v1".toHttpUrl()
        private const val DEFAULT_SESSION_TIMEOUT_SECONDS = 900L

        /** Legge la cache senza rete: usata anche dal salvaschermo. */
        fun cachedPhotos(cacheRoot: File): List<PhotoItem> {
            val directory = File(cacheRoot, CACHE_DIR)
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
    }
}
