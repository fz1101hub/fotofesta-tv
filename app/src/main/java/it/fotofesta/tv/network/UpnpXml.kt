package it.fotofesta.tv.network

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Server multimediale della rete di casa con il servizio ContentDirectory, che elenca cartelle e file. */
data class MediaServer(
    val udn: String,
    val name: String,
    /** Indirizzo della descrizione del dispositivo (LOCATION di SSDP). */
    val location: String,
    /** Indirizzo a cui mandare le richieste Browse. */
    val controlUrl: String,
)

/**
 * Cartella di un server multimediale. [musicOrVideo]: album, artisti, generi e playlist, che non
 * contengono foto; la ricerca delle foto li salta.
 */
data class MediaContainer(val id: String, val title: String, val musicOrVideo: Boolean = false)

/** File di un server multimediale, con l'indirizzo da cui scaricarlo e il tipo MIME. */
data class MediaFile(val id: String, val title: String, val url: String, val mimeType: String)

/** Contenuto di una pagina di Browse: cartelle, file e quanti elementi ha in tutto la cartella. */
data class BrowsePage(
    val containers: List<MediaContainer>,
    val files: List<MediaFile>,
    val returned: Int,
    val total: Int,
)

/** XML di UPnP: descrizione del dispositivo, richiesta e risposta Browse, elenco DIDL-Lite. */
object UpnpXml {
    private const val CONTENT_DIRECTORY_PREFIX = "urn:schemas-upnp-org:service:ContentDirectory:"

    /** Classi UPnP delle cartelle senza foto (gli album di foto sono object.container.album.photoAlbum). */
    private val NOT_PHOTO_CONTAINERS = listOf(
        "object.container.album.musicAlbum",
        "object.container.person",
        "object.container.genre",
        "object.container.playlistContainer",
    )

    /** Descrizione del dispositivo: nome, UDN e indirizzo del ContentDirectory; null se non ce l'ha. */
    fun parseDevice(xml: ByteArray, location: String): MediaServer? {
        val root = parse(xml)
        val device = root.descendants("device").firstOrNull() ?: return null
        val udn = device.child("UDN")?.text()?.takeIf { it.isNotEmpty() } ?: return null
        val name = device.child("friendlyName")?.text()?.takeIf { it.isNotEmpty() } ?: udn
        // Il servizio può stare in un dispositivo incluso (deviceList), come nei NAS con più funzioni.
        val service = root.descendants("service").firstOrNull {
            it.child("serviceType")?.text()?.startsWith(CONTENT_DIRECTORY_PREFIX) == true
        } ?: return null
        val control = service.child("controlURL")?.text()?.takeIf { it.isNotEmpty() } ?: return null
        val base = root.child("URLBase")?.text()?.takeIf { it.isNotEmpty() } ?: location
        val controlUrl = resolve(base, control) ?: return null
        return MediaServer(udn = udn, name = name, location = location, controlUrl = controlUrl)
    }

    /** Busta SOAP di Browse per i figli diretti di [objectId]. */
    fun browseRequest(objectId: String, start: Int, count: Int): String =
        """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            "<s:Body>" +
            """<u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">""" +
            "<ObjectID>${escape(objectId)}</ObjectID>" +
            "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
            "<Filter>*</Filter>" +
            "<StartingIndex>$start</StartingIndex>" +
            "<RequestedCount>$count</RequestedCount>" +
            "<SortCriteria></SortCriteria>" +
            "</u:Browse>" +
            "</s:Body>" +
            "</s:Envelope>"

    const val BROWSE_ACTION = "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""

    /**
     * Risposta di Browse: l'elenco DIDL-Lite dentro Result, già decodificato. Tiene i file con un tipo
     * MIME accettato da [acceptsMime]; [baseUrl] risolve gli indirizzi relativi.
     */
    fun parseBrowseResponse(xml: ByteArray, baseUrl: String, acceptsMime: (String) -> Boolean): BrowsePage {
        val root = parse(xml)
        root.descendants("Fault").firstOrNull()?.let {
            val code = it.descendants("errorCode").firstOrNull()?.text()
            throw IOException("Browse rifiutato" + (code?.let { value -> " ($value)" } ?: ""))
        }
        val result = root.descendants("Result").firstOrNull()?.text() ?: throw IOException("Risposta Browse senza Result")
        val returned = root.descendants("NumberReturned").firstOrNull()?.text()?.toIntOrNull() ?: 0
        val total = root.descendants("TotalMatches").firstOrNull()?.text()?.toIntOrNull() ?: 0
        if (result.isBlank()) return BrowsePage(emptyList(), emptyList(), returned, total)
        val didl = parse(result.toByteArray(Charsets.UTF_8))
        val containers = didl.children("container").mapNotNull { container ->
            val id = container.getAttribute("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val upnpClass = container.child("class")?.text().orEmpty()
            MediaContainer(
                id,
                container.child("title")?.text().orEmpty().ifEmpty { id },
                musicOrVideo = NOT_PHOTO_CONTAINERS.any { upnpClass.startsWith(it) },
            )
        }
        val files = didl.children("item").mapNotNull { item ->
            val id = item.getAttribute("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val resource = bestResource(item, acceptsMime) ?: return@mapNotNull null
            val url = resolve(baseUrl, resource.first) ?: return@mapNotNull null
            MediaFile(id, item.child("title")?.text().orEmpty().ifEmpty { id }, url, resource.second)
        }
        return BrowsePage(containers, files, returned, total)
    }

    /**
     * Fra le versioni di un file (originale, miniature, conversioni) sceglie la più grande con un tipo
     * accettato. Le miniature DLNA (*_TN, JPEG_SM) vanno per ultime; senza risoluzione dichiarata una
     * versione conta come originale.
     */
    private fun bestResource(item: Element, acceptsMime: (String) -> Boolean): Pair<String, String>? {
        data class Candidate(val url: String, val mime: String, val thumbnail: Boolean, val pixels: Long, val order: Int)
        val candidates = item.children("res").mapIndexedNotNull { index, res ->
            val info = res.getAttribute("protocolInfo").split(':')
            if (info.size < 3 || !info[0].equals("http-get", ignoreCase = true)) return@mapIndexedNotNull null
            val mime = info[2].substringBefore(';').trim().lowercase()
            val url = res.text().takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            if (!acceptsMime(mime)) return@mapIndexedNotNull null
            val profile = info.getOrNull(3).orEmpty().uppercase()
            val thumbnail = "_TN" in profile || "JPEG_SM" in profile
            val pixels = res.getAttribute("resolution").split('x', 'X').takeIf { it.size == 2 }
                ?.let { (w, h) -> (w.trim().toLongOrNull() ?: 0) * (h.trim().toLongOrNull() ?: 0) }
                ?.takeIf { it > 0 } ?: Long.MAX_VALUE
            Candidate(url, mime, thumbnail, pixels, index)
        }
        val best = candidates.sortedWith(
            compareBy<Candidate> { it.thumbnail }.thenByDescending { it.pixels }.thenBy { it.order },
        ).firstOrNull() ?: return null
        return best.url to best.mime
    }

    private fun resolve(base: String, reference: String): String? = runCatching {
        URI(base.trim()).resolve(reference.trim()).toString()
    }.getOrNull()?.takeIf { it.startsWith("http://", ignoreCase = true) }

    private fun escape(text: String): String = buildString {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }

    /**
     * Le risposte UPnP non hanno DOCTYPE: si rifiutano a monte, così nessuna entità esterna può far
     * leggere file o indirizzi, anche con parser che non permettono di disattivarle.
     */
    private fun parse(xml: ByteArray): Element {
        val head = String(xml, Charsets.UTF_8)
        if (head.contains("<!DOCTYPE", ignoreCase = true) || head.contains("<!ENTITY", ignoreCase = true)) {
            throw IOException("XML con DOCTYPE non accettato")
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
        } catch (failure: Exception) {
            throw IOException("XML non valido", failure)
        }
    }

    private val Node.name: String get() = localName ?: nodeName.substringAfter(':')

    private fun Element.children(name: String): List<Element> = buildList {
        var node = firstChild
        while (node != null) {
            if (node is Element && node.name == name) add(node)
            node = node.nextSibling
        }
    }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private fun Element.descendants(name: String): List<Element> = buildList {
        fun visit(element: Element) {
            var node = element.firstChild
            while (node != null) {
                if (node is Element) {
                    if (node.name == name) add(node)
                    visit(node)
                }
                node = node.nextSibling
            }
        }
        visit(this@descendants)
    }

    private fun Element.text(): String = textContent.orEmpty().trim()
}
