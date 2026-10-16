package it.fotofesta.tv.network

import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Cartella di rete scelta: basta a ritrovarla anche se il server cambia indirizzo IP. */
data class NetworkFolder(
    val udn: String,
    val location: String,
    val containerId: String,
    val title: String,
)

/**
 * Cartelle dei server multimediali della rete di casa (NAS, router, PC, Plex, Jellyfin…) con DLNA:
 * ricerca dei server, elenco di una cartella e foto di una cartella con le sottocartelle.
 * Le chiamate sono bloccanti: vanno fatte fuori dal thread principale.
 */
class NetworkMedia(
    private val http: LanHttp,
    /** Tipi MIME delle foto che la TV sa decodificare. */
    private val imageMimeTypes: Set<String>,
    /** Dimensioni dello schermo: le foto usano la versione del server che basta a riempirlo. */
    private val screen: ScreenSize? = null,
    private val search: () -> List<Ssdp.Answer> = { Ssdp.search() },
) {
    private val servers = ConcurrentHashMap<String, MediaServer>()

    /** Server con il servizio ContentDirectory, in ordine di nome. */
    fun discover(): List<MediaServer> {
        val found = search()
            .distinctBy { it.location }
            .mapNotNull { answer -> runCatching { describe(answer.location) }.getOrNull() }
            .distinctBy { it.udn }
        found.forEach { servers[it.udn] = it }
        return found.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** Server già trovato in questa sessione. */
    fun known(udn: String): MediaServer? = servers[udn]

    /**
     * Server della cartella salvata: prima all'indirizzo noto, poi (se ha cambiato IP) cercandolo in
     * rete per UDN.
     */
    fun find(udn: String, location: String): MediaServer? {
        servers[udn]?.let { return it }
        runCatching { describe(location) }.getOrNull()?.takeIf { it.udn == udn }?.let {
            servers[udn] = it
            return it
        }
        return discover().firstOrNull { it.udn == udn }
    }

    private fun describe(location: String): MediaServer? = http.get(location).use { response ->
        if (!response.isSuccessful) return null
        UpnpXml.parseDevice(response.bytes(MAX_DESCRIPTION_BYTES), location)
    }

    /** Sottocartelle e foto di una cartella ("0" è la cartella principale del server). */
    fun list(server: MediaServer, containerId: String): BrowsePage {
        val containers = mutableListOf<MediaContainer>()
        val files = mutableListOf<MediaFile>()
        var start = 0
        var total: Int
        do {
            val page = browse(server, containerId, start)
            containers += page.containers
            files += page.files
            // Alcuni server dichiarano TotalMatches 0: ci si ferma quando una pagina arriva vuota.
            total = page.total
            start += page.returned
            if (page.returned <= 0) break
        } while (start < total && start < MAX_CHILDREN)
        return BrowsePage(containers, files, start, total)
    }

    /**
     * Foto e video della cartella e delle sottocartelle, al massimo [limit], nell'ordine del server.
     * [checkCancelled] viene chiamato a ogni cartella e può lanciare un'eccezione per interrompere.
     */
    fun scanPhotos(server: MediaServer, containerId: String, limit: Int = MAX_PHOTOS, checkCancelled: () -> Unit = {}): List<PhotoItem> {
        val queue = ArrayDeque<String>().apply { add(containerId) }
        val visited = HashSet<String>()
        val photos = LinkedHashMap<String, PhotoItem>()
        var first = true
        while (queue.isNotEmpty() && photos.size < limit && visited.size < MAX_FOLDERS) {
            checkCancelled()
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            val page = try {
                list(server, current)
            } catch (failure: IOException) {
                // La cartella scelta deve leggersi; una sottocartella illeggibile si salta.
                if (first) throw failure
                continue
            }
            first = false
            for (file in page.files) {
                if (photos.size >= limit) break
                photos.putIfAbsent(file.url, file.toPhotoItem(server))
            }
            // Sui NAS con molta musica gli album e gli artisti sono migliaia: senza foto, si saltano.
            page.containers.filterNot { it.musicOrVideo }.forEach { queue.addLast(it.id) }
        }
        return photos.values.toList()
    }

    private fun browse(server: MediaServer, containerId: String, start: Int): BrowsePage {
        val body = UpnpXml.browseRequest(containerId, start, PAGE_SIZE).toByteArray(Charsets.UTF_8)
        val headers = mapOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "SOAPAction" to UpnpXml.BROWSE_ACTION,
        )
        return http.post(server.controlUrl, headers, body).use { response ->
            // Gli errori SOAP arrivano con 500 e un Fault nel corpo, che parseBrowseResponse spiega.
            if (!response.isSuccessful && response.status != 500) throw IOException("Browse: HTTP ${response.status}")
            val page = UpnpXml.parseBrowseResponse(response.bytes(MAX_BROWSE_BYTES), server.controlUrl, ::acceptsMedia, screen)
            // Solo file raggiungibili dalla TV: un indirizzo fuori dalla rete di casa non si carica.
            page.copy(files = page.files.filter { http.accepts(it.url) })
        }
    }

    /** Foto che la TV sa decodificare e video (il lettore salta quelli che la TV non sa riprodurre). */
    private fun acceptsMedia(mime: String): Boolean = mime in imageMimeTypes || mime.startsWith("video/")

    private fun MediaFile.toPhotoItem(server: MediaServer) = PhotoItem(
        id = "net:${server.udn}:$id",
        title = title.substringBeforeLast('.').ifBlank { title },
        ref = PhotoRef.NetworkPhoto(url),
        source = SourceType.NETWORK,
        video = mimeType.startsWith("video/"),
    )

    companion object {
        const val ROOT = "0"
        private const val PAGE_SIZE = 500
        private const val MAX_CHILDREN = 5_000
        private const val MAX_PHOTOS = 10_000
        private const val MAX_FOLDERS = 2_000
        private const val MAX_DESCRIPTION_BYTES = 256 * 1024
        private const val MAX_BROWSE_BYTES = 8 * 1024 * 1024

        /** Tipi MIME delle foto decodificabili dalla versione di Android indicata (come FileBrowser). */
        fun imageMimeTypes(sdk: Int): Set<String> = buildSet {
            addAll(listOf("image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/x-ms-bmp"))
            if (sdk >= 28) addAll(listOf("image/heic", "image/heif"))
            if (sdk >= 31) add("image/avif")
        }
    }
}

/** Cartella di un server nel browser di cartelle: "upnp:<udn>#<id>" (i percorsi dei dischi iniziano con "/"). */
object NetworkPath {
    private const val PREFIX = "upnp:"

    fun of(udn: String, containerId: String) = "$PREFIX$udn#$containerId"

    /** UDN del server e identificativo della cartella; null se non è una cartella di rete. */
    fun parse(path: String): Pair<String, String>? {
        if (!path.startsWith(PREFIX)) return null
        val rest = path.removePrefix(PREFIX)
        val hash = rest.indexOf('#')
        if (hash <= 0 || hash == rest.lastIndex) return null
        return rest.substring(0, hash) to rest.substring(hash + 1)
    }
}
