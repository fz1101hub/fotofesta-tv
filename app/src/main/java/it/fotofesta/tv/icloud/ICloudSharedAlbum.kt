package it.fotofesta.tv.icloud

import it.fotofesta.tv.google.await
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import it.fotofesta.tv.network.ScreenSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/** Perché un album condiviso non si può leggere, per un messaggio chiaro sulla TV. */
class ICloudAlbumException(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    enum class Reason {
        /** Link sbagliato, album eliminato o "Sito web pubblico" disattivato. */
        NOT_FOUND,

        /** iCloud non risponde: rete assente o problema temporaneo. */
        UNREACHABLE,

        /** La risposta non ha più la forma attesa: Apple ha cambiato la pagina pubblica degli album. */
        CHANGED,
    }
}

/** Link pubblico di un album condiviso: "https://www.icloud.com/sharedalbum/#B0aGWZuqDGKfHtd". */
object ICloudLink {
    private val TOKEN = Regex("^[A-Za-z0-9]{8,40}$")
    private val LEADING_CODE = Regex("^[A-Za-z0-9]+")
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /**
     * Codice dell'album da un link completo (anche con la lingua, "…/sharedalbum/it-it/#…", o dentro
     * un messaggio copiato per intero) o dal solo codice.
     */
    fun token(input: String): String? {
        val text = input.trim()
        return when {
            // Il codice finisce al primo carattere che non è una lettera o una cifra (";", spazio…).
            text.contains("sharedalbum", ignoreCase = true) ->
                LEADING_CODE.find(text.substringAfter("sharedalbum").substringAfter('#', ""))?.value
            text.startsWith("#") -> LEADING_CODE.find(text.drop(1))?.value
            // Il solo codice, scritto sulla TV.
            else -> text
        }?.takeIf { TOKEN.matches(it) }
    }

    /** Server iCloud dell'album, ricavato dal codice come fa la pagina web di Apple. */
    fun partitionHost(token: String): String {
        fun base62(text: String) = text.fold(0) { value, char -> value * 62 + DIGITS.indexOf(char).coerceAtLeast(0) }
        val partition = if (token.first() == 'A') base62(token.substring(1, 2)) else base62(token.substring(1, 3))
        return "p%02d-sharedstreams.icloud.com".format(partition)
    }
}

/** Una versione di una foto o di un video dell'album (miniatura, grande, video 720p…). */
data class ICloudDerivative(val key: String, val checksum: String, val width: Int, val height: Int, val fileSize: Long)

data class ICloudItem(
    val guid: String,
    val caption: String,
    val contributor: String?,
    val video: Boolean,
    val derivatives: List<ICloudDerivative>,
    val createdMillis: Long?,
) {
    /** Per le foto la versione più leggera che copre lo schermo, altrimenti la più grande. */
    fun photoDerivative(screen: ScreenSize?): ICloudDerivative? {
        val images = derivatives.filter { it.width > 0 && it.height > 0 && !isVideoRendition(it) }
        if (screen != null) {
            images.filter { maxOf(it.width, it.height) >= screen.longEdge && minOf(it.width, it.height) >= screen.shortEdge }
                .minByOrNull { it.width.toLong() * it.height }
                ?.let { return it }
        }
        return images.maxByOrNull { it.width.toLong() * it.height }
    }

    /** Per i video il file più grande ("720p", "1080p"…); il fotogramma di copertina non conta. */
    fun videoDerivative(): ICloudDerivative? {
        derivatives.filter(::isVideoRendition)
            .maxByOrNull { it.key.dropLast(1).toIntOrNull() ?: 0 }
            ?.let { return it }
        return derivatives.filterNot { it.key.equals(POSTER, ignoreCase = true) }.maxByOrNull { it.fileSize }
    }

    private fun isVideoRendition(derivative: ICloudDerivative) = video && VIDEO_KEY.matches(derivative.key)

    private companion object {
        const val POSTER = "PosterFrame"
        val VIDEO_KEY = Regex("^\\d{3,4}[pP]$")
    }
}

data class ICloudAlbum(
    val token: String,
    /** Server che ha risposto, dopo l'eventuale reindirizzamento 330. */
    val host: String,
    val name: String,
    val owner: String?,
    val items: List<ICloudItem>,
) {
    val photoCount: Int get() = items.count { !it.video }
    val videoCount: Int get() = items.count { it.video }

    fun toPhotos(): List<PhotoItem> = items.map { item ->
        PhotoItem(
            id = "icloud:${item.guid}",
            title = item.caption,
            ref = PhotoRef.ICloudPhoto(item.guid),
            source = SourceType.ICLOUD,
            sender = item.contributor,
            video = item.video,
        )
    }
}

/**
 * Legge un album condiviso di iCloud con "Sito web pubblico" attivo, come la pagina web di Apple:
 * `webstream` per l'elenco e `webasseturls` per gli indirizzi dei file. Apple non documenta queste
 * chiamate: se cambiano, l'album non si legge più ([ICloudAlbumException.Reason.CHANGED]).
 */
class ICloudAlbumClient(
    private val http: OkHttpClient,
    private val scheme: String = "https",
    private val firstHost: (String) -> String = ICloudLink::partitionHost,
    /** Server ammessi dopo un reindirizzamento e per i file: solo quelli di Apple. */
    private val allowedApiHost: (String) -> Boolean = { API_HOST.matches(it) },
    private val allowedFileHost: (String) -> Boolean = { host -> FILE_HOSTS.any { host == it.removePrefix(".") || host.endsWith(it) } },
) {
    suspend fun album(token: String): ICloudAlbum {
        var host = firstHost(token)
        repeat(MAX_REDIRECTS + 1) {
            val (code, body) = post(host, token, "webstream", """{"streamCtag":null}""")
            when {
                code == 330 -> {
                    // Dal 2024 Apple indica così il server giusto per l'album.
                    host = parse { JSONObject(body).getString("X-Apple-MMe-Host") }.trim()
                    if (!allowedApiHost(host)) throw ICloudAlbumException(ICloudAlbumException.Reason.CHANGED)
                }
                code == 200 -> return parse { parseAlbum(token, host, JSONObject(body)) }
                code in 400..499 -> throw ICloudAlbumException(ICloudAlbumException.Reason.NOT_FOUND)
                else -> throw ICloudAlbumException(ICloudAlbumException.Reason.UNREACHABLE, IOException("HTTP $code"))
            }
        }
        throw ICloudAlbumException(ICloudAlbumException.Reason.CHANGED)
    }

    /** Indirizzi dei file (per checksum) delle foto indicate; scadono dopo qualche tempo. */
    suspend fun assetUrls(album: ICloudAlbum, guids: List<String>): Map<String, String> {
        val body = JSONObject().put("photoGuids", JSONArray(guids)).toString()
        val (code, text) = post(album.host, album.token, "webasseturls", body)
        if (code in 400..499) throw ICloudAlbumException(ICloudAlbumException.Reason.NOT_FOUND)
        if (code != 200) throw ICloudAlbumException(ICloudAlbumException.Reason.UNREACHABLE, IOException("HTTP $code"))
        return parse {
            val items = JSONObject(text).getJSONObject("items")
            buildMap {
                for (checksum in items.keys()) {
                    val item = items.getJSONObject(checksum)
                    val location = item.getString("url_location")
                    val path = item.getString("url_path")
                    if (!allowedFileHost(location) || !path.startsWith("/")) throw ICloudAlbumException(ICloudAlbumException.Reason.CHANGED)
                    put(checksum, "$scheme://$location$path")
                }
            }
        }
    }

    private suspend fun post(host: String, token: String, action: String, body: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("$scheme://$host/$token/sharedstreams/$action")
            .header("Origin", "https://www.icloud.com")
            .header("Referer", "https://www.icloud.com/sharedalbum/")
            .header("Accept", "*/*")
            .post(body.toRequestBody("text/plain".toMediaType()))
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (failure: IOException) {
            throw ICloudAlbumException(ICloudAlbumException.Reason.UNREACHABLE, failure)
        }
        return withContext(Dispatchers.IO) {
            response.use {
                val text = try {
                    it.body?.source()?.let { source ->
                        source.request(MAX_RESPONSE_BYTES + 1)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) throw ICloudAlbumException(ICloudAlbumException.Reason.CHANGED)
                        source.buffer.readUtf8()
                    }.orEmpty()
                } catch (failure: IOException) {
                    if (failure is ICloudAlbumException) throw failure
                    throw ICloudAlbumException(ICloudAlbumException.Reason.UNREACHABLE, failure)
                }
                it.code to text
            }
        }
    }

    private fun parseAlbum(token: String, host: String, json: JSONObject): ICloudAlbum {
        val photos = json.getJSONArray("photos")
        val items = (0 until photos.length()).map { index ->
            val photo = photos.getJSONObject(index)
            val derivatives = photo.getJSONObject("derivatives")
            ICloudItem(
                guid = photo.getString("photoGuid"),
                caption = photo.optString("caption").trim(),
                contributor = photo.optString("contributorFirstName").trim().ifEmpty {
                    photo.optString("contributorFullName").trim()
                }.ifEmpty { null },
                video = photo.optString("mediaAssetType").equals("video", ignoreCase = true),
                derivatives = derivatives.keys().asSequence().map { key ->
                    val value = derivatives.getJSONObject(key)
                    ICloudDerivative(
                        key = key,
                        checksum = value.getString("checksum"),
                        width = value.optString("width").toIntOrNull() ?: 0,
                        height = value.optString("height").toIntOrNull() ?: 0,
                        fileSize = value.optString("fileSize").toLongOrNull() ?: 0,
                    )
                }.toList(),
                createdMillis = runCatching { Instant.parse(photo.optString("dateCreated")).toEpochMilli() }.getOrNull(),
            )
        }
        val owner = listOf(json.optString("userFirstName"), json.optString("userLastName"))
            .map(String::trim).filter(String::isNotEmpty).joinToString(" ").ifEmpty { null }
        return ICloudAlbum(
            token = token,
            host = host,
            name = json.optString("streamName").trim(),
            owner = owner,
            // In ordine di scatto, come si racconta una festa; senza data resta l'ordine di Apple.
            items = items.withIndex().sortedWith(compareBy({ it.value.createdMillis ?: Long.MAX_VALUE }, { it.index })).map { it.value },
        )
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (failure: JSONException) {
        throw ICloudAlbumException(ICloudAlbumException.Reason.CHANGED, failure)
    }

    private companion object {
        const val MAX_REDIRECTS = 2
        const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
        val API_HOST = Regex("^p\\d{1,3}-sharedstreams\\.icloud\\.com$")
        val FILE_HOSTS = listOf(".icloud-content.com", ".icloud.com")
    }
}

/**
 * Album condiviso in uso, con gli indirizzi dei file: si chiedono a gruppi (la foto richiesta e le
 * successive) e si rinnovano quando scadono o quando un indirizzo non funziona più.
 */
class ICloudAlbumStream(
    private val client: ICloudAlbumClient,
    private val screen: ScreenSize?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val urls = HashMap<String, Pair<String, Long>>()

    @Volatile
    var album: ICloudAlbum? = null
        private set

    /** Album mostrato da ora; gli indirizzi già noti restano validi per le foto che non cambiano. */
    fun use(value: ICloudAlbum?) {
        album = value
    }

    /** Indirizzo del file della foto o del video [guid]; con [failed] l'indirizzo che non ha funzionato. */
    suspend fun mediaUrl(guid: String, failed: String? = null): String = lock.withLock {
        val current = album ?: throw ICloudAlbumException(ICloudAlbumException.Reason.NOT_FOUND)
        val index = current.items.indexOfFirst { it.guid == guid }
        if (index < 0) throw ICloudAlbumException(ICloudAlbumException.Reason.NOT_FOUND)
        val item = current.items[index]
        val derivative = (if (item.video) item.videoDerivative() else item.photoDerivative(screen))
            ?: throw ICloudAlbumException(ICloudAlbumException.Reason.CHANGED)
        urls[derivative.checksum]?.let { (url, fetchedAt) ->
            if (url != failed && clock() - fetchedAt < URL_LIFETIME_MS) return@withLock url
        }
        val batch = (current.items.subList(index, current.items.size) + current.items.subList(0, index)).take(BATCH)
        val fetched = client.assetUrls(current, batch.map { it.guid })
        val now = clock()
        fetched.forEach { (checksum, url) -> urls[checksum] = url to now }
        urls[derivative.checksum]?.first ?: throw ICloudAlbumException(ICloudAlbumException.Reason.CHANGED)
    }

    companion object {
        /** Apple non dice quanto valgono gli indirizzi: si rinnovano prima, a ogni mezz'ora. */
        const val URL_LIFETIME_MS = 30 * 60 * 1000L
        /** Come la pagina web di Apple, al massimo 25 elementi per richiesta. */
        const val BATCH = 25
    }
}
