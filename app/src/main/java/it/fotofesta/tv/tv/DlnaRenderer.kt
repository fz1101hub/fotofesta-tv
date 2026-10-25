package it.fotofesta.tv.tv

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import it.fotofesta.tv.network.LanHttp
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * La TV come schermo DLNA (MediaRenderer): i telefoni con "Trasmetti" o "Riproduci su" DLNA la
 * vedono tra gli schermi e le mandano foto. Ogni foto arrivata si scarica (al massimo
 * [MAX_PHOTO_BYTES]) e va a [onPhoto], che la mette tra le foto della festa.
 *
 * Solo foto: un video o un brano mandato alla TV viene rifiutato con l'errore UPnP 714.
 */
class DlnaRenderer(
    private val context: Context,
    private val name: String,
    private val http: LanHttp,
    private val onPhoto: (bytes: ByteArray, title: String?) -> Unit,
) {
    private val uuid = UUID.nameUUIDFromBytes((context.packageName + ":renderer").toByteArray()).toString()
    private val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "fotofesta-dlna").apply { isDaemon = true } }
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var ssdp: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var location = ""
    @Volatile private var state = "STOPPED"
    @Volatile private var currentUri = ""

    /** Avvia il renderer sull'indirizzo [address] della TV; true se è partito. */
    @Synchronized
    fun start(address: Inet4Address): Boolean {
        if (running) return true
        return runCatching {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(address, 0))
            server = socket
            location = "http://${address.hostAddress}:${socket.localPort}/upnp/description.xml"
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifi?.createMulticastLock("fotofesta-dlna")?.apply { setReferenceCounted(false); acquire() }
            val multicast = MulticastSocket(SSDP_PORT)
            multicast.reuseAddress = true
            multicast.soTimeout = 1_000
            @Suppress("DEPRECATION")
            multicast.joinGroup(InetAddress.getByName(SSDP_ADDRESS))
            ssdp = multicast
            running = true
            pool.execute { acceptLoop(socket) }
            pool.execute { ssdpLoop(multicast) }
            true
        }.getOrElse { failure ->
            Log.w(TAG, "Schermo DLNA non avviato", failure)
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        if (running) runCatching { announce(alive = false) }
        running = false
        runCatching { server?.close() }
        runCatching { ssdp?.close() }
        runCatching { lock?.release() }
        server = null
        ssdp = null
        lock = null
    }

    private fun ssdpLoop(socket: MulticastSocket) {
        var lastNotify = 0L
        val buffer = ByteArray(2_048)
        while (running) {
            if (System.currentTimeMillis() - lastNotify > NOTIFY_MS) {
                runCatching { announce(alive = true) }
                lastNotify = System.currentTimeMillis()
            }
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                val target = Upnp.searchTarget(text) ?: continue
                val answer = Upnp.searchResponse(target, location, uuid).toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(answer, answer.size, packet.socketAddress))
            } catch (_: SocketTimeoutException) {
                // Nessuna ricerca: si ricontrolla se è ora di annunciarsi.
            } catch (failure: Exception) {
                if (running) Log.w(TAG, "SSDP", failure)
            }
        }
    }

    private fun announce(alive: Boolean) {
        val socket = ssdp ?: return
        val group = InetAddress.getByName(SSDP_ADDRESS)
        (listOf("upnp:rootdevice", "uuid:$uuid") + Upnp.TYPES.drop(1)).forEach { type ->
            val message = Upnp.notify(type, location, uuid, alive).toByteArray(Charsets.US_ASCII)
            socket.send(DatagramPacket(message, message.size, group, SSDP_PORT))
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            pool.execute { client.use(::serve) }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, path) = lines.first().split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_SOAP_BYTES) ?: 0
        val body = ByteArray(length).also { buffer ->
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
        }.toString(Charsets.UTF_8)
        val (status, type, content) = when {
            method == "GET" && path == "/upnp/description.xml" -> Triple(200, XML, Upnp.description(name, uuid))
            method == "GET" && path == "/upnp/avtransport.xml" -> Triple(200, XML, Upnp.scpd(AV_ACTIONS))
            method == "GET" && path == "/upnp/connection.xml" -> Triple(200, XML, Upnp.scpd(CM_ACTIONS))
            method == "GET" && path == "/upnp/rendering.xml" -> Triple(200, XML, Upnp.scpd(RC_ACTIONS))
            method == "POST" && path.startsWith("/upnp/") -> control(path, Upnp.soapAction(headers["soapaction"]), body)
            // Eventi: si accetta l'iscrizione senza mandarne (i telefoni chiedono lo stato quando serve).
            method == "SUBSCRIBE" || method == "UNSUBSCRIBE" -> Triple(200, XML, "")
            else -> Triple(404, XML, "")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val extra = if (method == "SUBSCRIBE") "SID: uuid:${UUID.randomUUID()}\r\nTIMEOUT: Second-1800\r\n" else ""
        val reply = "HTTP/1.1 $status ${if (status == 200) "OK" else if (status == 500) "Internal Server Error" else "Not Found"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${bytes.size}\r\nServer: Android/1 UPnP/1.0 FotoFesta/1\r\n${extra}Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(reply.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun control(path: String, action: String?, body: String): Triple<Int, String, String> {
        val service = when (path) {
            "/upnp/avtransport" -> Upnp.AV_TRANSPORT
            "/upnp/connection" -> Upnp.CONNECTION_MANAGER
            else -> Upnp.RENDERING_CONTROL
        }
        val name = action ?: return Triple(500, XML, fault(401))
        val values: Map<String, String> = when (name) {
            "SetAVTransportURI" -> {
                val uri = Upnp.argument(body, "CurrentURI").orEmpty()
                val metadata = Upnp.argument(body, "CurrentURIMetaData").orEmpty()
                if (!uri.startsWith("http://") && !uri.startsWith("https://")) return Triple(500, XML, fault(716))
                // Solo foto: i metadati DIDL dicono se è un video o un brano.
                if (metadata.contains("object.item.videoItem") || metadata.contains("object.item.audioItem")) return Triple(500, XML, fault(714))
                currentUri = uri
                val title = Upnp.argument(Upnp.unescape(metadata), "dc:title") ?: Upnp.argument(Upnp.unescape(metadata), "title")
                pool.execute { download(uri, title) }
                state = "PLAYING"
                emptyMap()
            }
            "Play" -> emptyMap<String, String>().also { state = "PLAYING" }
            "Pause" -> emptyMap<String, String>().also { state = "PAUSED_PLAYBACK" }
            "Stop" -> emptyMap<String, String>().also { state = "STOPPED" }
            "GetTransportInfo" -> mapOf("CurrentTransportState" to state, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            "GetMediaInfo" -> mapOf("NrTracks" to "1", "MediaDuration" to "0:00:00", "CurrentURI" to currentUri, "CurrentURIMetaData" to "", "PlayMedium" to "NETWORK", "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED")
            "GetPositionInfo" -> mapOf("Track" to "1", "TrackDuration" to "0:00:00", "TrackMetaData" to "", "TrackURI" to currentUri, "RelTime" to "0:00:00", "AbsTime" to "0:00:00", "RelCount" to "0", "AbsCount" to "0")
            "GetTransportSettings" -> mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> mapOf("Actions" to "Play,Stop,Pause")
            "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to SINK)
            "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
            "GetCurrentConnectionInfo" -> mapOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "", "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK")
            "GetVolume" -> mapOf("CurrentVolume" to "50")
            "GetMute" -> mapOf("CurrentMute" to "0")
            "SetVolume", "SetMute", "Seek", "Next", "Previous" -> emptyMap()
            else -> return Triple(500, XML, fault(401))
        }
        return Triple(200, XML, Upnp.response(service, name, values))
    }

    /** Solo indirizzi della rete di casa: un telefono non può far scaricare alla TV pagine di Internet. */
    private fun download(uri: String, title: String?) {
        runCatching {
            http.get(uri).use { response ->
                if (!response.isSuccessful) return
                val type = response.headers["content-type"]?.substringBefore('/')?.trim()
                if (type != null && type != "image") return
                val bytes = response.bytes(MAX_PHOTO_BYTES)
                onPhoto(bytes, title?.takeIf { it.isNotBlank() })
            }
        }.onFailure { Log.w(TAG, "Foto DLNA non scaricata", it) }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0
        val end = "\r\n\r\n"
        while (out.size() < MAX_HEAD_BYTES) {
            val value = input.read()
            if (value < 0) return null
            out.write(value)
            matched = if (value.toChar() == end[matched]) matched + 1 else if (value.toChar() == '\r') 1 else 0
            if (matched == end.length) return out.toString(Charsets.US_ASCII.name())
        }
        return null
    }

    private fun fault(code: Int): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>Error</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""

    companion object {
        private const val TAG = "DlnaRenderer"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val NOTIFY_MS = 60_000L
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val MAX_SOAP_BYTES = 64 * 1024
        const val MAX_PHOTO_BYTES = 20 * 1024 * 1024
        private const val XML = "text/xml; charset=\"utf-8\""
        private const val SINK = "http-get:*:image/jpeg:*,http-get:*:image/png:*,http-get:*:image/webp:*,http-get:*:image/heic:*"
        private val AV_ACTIONS = mapOf(
            "SetAVTransportURI" to listOf("InstanceID", "CurrentURI", "CurrentURIMetaData"),
            "Play" to listOf("InstanceID", "Speed"),
            "Pause" to listOf("InstanceID"),
            "Stop" to listOf("InstanceID"),
            "GetTransportInfo" to listOf("InstanceID", "<CurrentTransportState", "<CurrentTransportStatus", "<CurrentSpeed"),
            "GetMediaInfo" to listOf("InstanceID", "<NrTracks", "<MediaDuration", "<CurrentURI", "<CurrentURIMetaData", "<PlayMedium", "<RecordMedium", "<WriteStatus"),
            "GetPositionInfo" to listOf("InstanceID", "<Track", "<TrackDuration", "<TrackMetaData", "<TrackURI", "<RelTime", "<AbsTime", "<RelCount", "<AbsCount"),
            "GetTransportSettings" to listOf("InstanceID", "<PlayMode", "<RecQualityMode"),
            "GetCurrentTransportActions" to listOf("InstanceID", "<Actions"),
        )
        private val CM_ACTIONS = mapOf(
            "GetProtocolInfo" to listOf("<Source", "<Sink"),
            "GetCurrentConnectionIDs" to listOf("<ConnectionIDs"),
            "GetCurrentConnectionInfo" to listOf("ConnectionID", "<RcsID", "<AVTransportID", "<ProtocolInfo", "<PeerConnectionManager", "<PeerConnectionID", "<Direction", "<Status"),
        )
        private val RC_ACTIONS = mapOf(
            "GetVolume" to listOf("InstanceID", "Channel", "<CurrentVolume"),
            "SetVolume" to listOf("InstanceID", "Channel", "DesiredVolume"),
            "GetMute" to listOf("InstanceID", "Channel", "<CurrentMute"),
            "SetMute" to listOf("InstanceID", "Channel", "DesiredMute"),
        )
    }
}
