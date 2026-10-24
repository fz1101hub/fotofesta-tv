package it.fotofesta.tv.tv

import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Flusso MJPEG di una "IP Webcam" su un vecchio telefono (multipart/x-mixed-replace): i fotogrammi
 * sono JPEG uno dopo l'altro, separati da intestazioni. Si cercano i marcatori d'inizio (FF D8) e di
 * fine (FF D9) del JPEG, così funziona con qualunque separatore. I byte arrivano a pezzi con [feed];
 * ogni fotogramma completo va a [onFrame].
 */
class MjpegParser(private val maxFrameBytes: Int = MAX_FRAME_BYTES, private val onFrame: (ByteArray) -> Unit) {
    private val frame = ByteArrayOutputStream()
    private var inFrame = false
    private var previous = -1

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (index in 0 until length) {
            val value = bytes[index].toInt() and 0xFF
            if (!inFrame) {
                if (previous == 0xFF && value == 0xD8) {
                    inFrame = true
                    frame.reset()
                    frame.write(0xFF)
                    frame.write(0xD8)
                }
            } else {
                frame.write(value)
                if (previous == 0xFF && value == 0xD9) {
                    inFrame = false
                    onFrame(frame.toByteArray())
                    frame.reset()
                } else if (frame.size() > maxFrameBytes) {
                    // Fotogramma troppo grande o flusso rovinato: si riparte dal prossimo inizio.
                    inFrame = false
                    frame.reset()
                }
            }
            previous = value
        }
    }

    companion object {
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}

/**
 * Applausometro: legge l'audio WAV (PCM a 16 bit) del microfono di un vecchio telefono e misura il
 * volume, da 0 (silenzio) a 1 (fortissimo), su finestre di [windowSamples] campioni.
 */
class WavMeter(private val windowSamples: Int = 1_600, private val onLevel: (Float) -> Unit) {
    private var header = ByteArray(0)
    private var headerDone = false
    private var pending = -1
    private var sum = 0.0
    private var count = 0

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        var start = 0
        if (!headerDone) {
            // L'intestazione RIFF/WAVE classica è di 44 byte, poi solo campioni.
            val needed = (WAV_HEADER - header.size).coerceAtMost(length)
            header += bytes.copyOfRange(0, needed)
            start = needed
            if (header.size < WAV_HEADER) return
            headerDone = true
        }
        for (index in start until length) {
            val value = bytes[index].toInt() and 0xFF
            if (pending < 0) {
                pending = value
                continue
            }
            val sample = ((value shl 8) or pending).toShort().toInt()
            pending = -1
            sum += sample.toDouble() * sample
            count++
            if (count >= windowSamples) {
                onLevel(level(sqrt(sum / count)))
                sum = 0.0
                count = 0
            }
        }
    }

    companion object {
        const val WAV_HEADER = 44

        /** Volume RMS in una scala 0–1 che segue l'orecchio: −50 dB è il silenzio, 0 dB il massimo. */
        fun level(rms: Double): Float {
            if (rms <= 0.0) return 0f
            val db = 20 * log10(rms / Short.MAX_VALUE)
            return ((db + 50) / 50).toFloat().coerceIn(0f, 1f)
        }
    }
}

/** Pulsanti quiz: chi preme per primo dopo il via. Squadre = gamepad, tappetini o telecomandi diversi. */
class BuzzerRound(private val maxTeams: Int = 4) {
    private val teams = ArrayList<Int>()
    private val scores = HashMap<Int, Int>()
    var armed = false
        private set
    var winner: Int? = null
        private set

    /** Squadre iscritte, in ordine di iscrizione (identificativo del dispositivo). */
    fun teams(): List<Int> = teams.toList()

    fun score(team: Int): Int = scores[team] ?: 0

    /** Un dispositivo preme un tasto: si iscrive se è nuovo; se il turno è aperto ed è il primo, vince. */
    fun press(device: Int): Boolean {
        if (device !in teams) {
            if (teams.size >= maxTeams) return false
            teams += device
        }
        if (!armed || winner != null) return false
        winner = device
        armed = false
        return true
    }

    /** Nuova domanda: si attende il primo che preme. */
    fun arm() {
        winner = null
        armed = true
    }

    /** La risposta della squadra di turno era giusta: un punto. */
    fun award() {
        winner?.let { scores[it] = (scores[it] ?: 0) + 1 }
    }

    fun reset() {
        teams.clear()
        scores.clear()
        winner = null
        armed = false
    }
}

/**
 * Pezzi di UPnP per far comparire la TV tra gli schermi DLNA dei telefoni (MediaRenderer): risposte
 * SSDP, descrizione del dispositivo e lettura delle azioni SOAP.
 */
object Upnp {
    const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    val TYPES = listOf("upnp:rootdevice", RENDERER, AV_TRANSPORT, CONNECTION_MANAGER, RENDERING_CONTROL)

    /** Il telefono cerca schermi: rispondere se cerca tutto, un renderer o uno dei nostri servizi. */
    fun searchTarget(request: String): String? {
        if (!request.startsWith("M-SEARCH", ignoreCase = true)) return null
        val target = header(request, "ST") ?: return null
        return when {
            target == "ssdp:all" -> RENDERER
            target in TYPES || target.startsWith("uuid:") -> target
            else -> null
        }
    }

    fun searchResponse(target: String, location: String, uuid: String): String =
        "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "EXT:\r\n" +
            "LOCATION: $location\r\n" +
            "SERVER: Android/1 UPnP/1.0 FotoFesta/1\r\n" +
            "ST: $target\r\n" +
            "USN: uuid:$uuid${if (target.startsWith("uuid:")) "" else "::$target"}\r\n\r\n"

    fun notify(type: String, location: String, uuid: String, alive: Boolean): String =
        "NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: $location\r\n" +
            "NT: $type\r\n" +
            "NTS: ${if (alive) "ssdp:alive" else "ssdp:byebye"}\r\n" +
            "SERVER: Android/1 UPnP/1.0 FotoFesta/1\r\n" +
            "USN: uuid:$uuid::$type\r\n\r\n"

    fun header(message: String, name: String): String? =
        message.lineSequence().firstNotNullOfOrNull { line ->
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals(name, ignoreCase = true)) line.substring(colon + 1).trim() else null
        }

    fun description(name: String, uuid: String): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>$RENDERER</deviceType>
<friendlyName>${escape(name)}</friendlyName>
<manufacturer>FotoFesta</manufacturer>
<modelName>FotoFesta TV</modelName>
<UDN>uuid:$uuid</UDN>
<serviceList>
<service><serviceType>$AV_TRANSPORT</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/upnp/avtransport.xml</SCPDURL><controlURL>/upnp/avtransport</controlURL><eventSubURL>/upnp/events</eventSubURL></service>
<service><serviceType>$CONNECTION_MANAGER</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/upnp/connection.xml</SCPDURL><controlURL>/upnp/connection</controlURL><eventSubURL>/upnp/events</eventSubURL></service>
<service><serviceType>$RENDERING_CONTROL</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><SCPDURL>/upnp/rendering.xml</SCPDURL><controlURL>/upnp/rendering</controlURL><eventSubURL>/upnp/events</eventSubURL></service>
</serviceList>
</device>
</root>"""

    /** Azione SOAP dall'intestazione SOAPACTION ("urn:...:AVTransport:1#SetAVTransportURI"). */
    fun soapAction(header: String?): String? = header?.trim('"', ' ')?.substringAfter('#', "")?.takeIf(String::isNotEmpty)

    /** Valore di un argomento SOAP (<CurrentURI>...</CurrentURI>), con le entità XML tradotte. */
    fun argument(body: String, name: String): String? {
        val match = Regex("<(?:\\w+:)?$name>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(body) ?: return null
        return unescape(match.groupValues[1].trim())
    }

    fun response(service: String, action: String, values: Map<String, String> = emptyMap()): String {
        val arguments = values.entries.joinToString("") { (key, value) -> "<$key>${escape(value)}</$key>" }
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:${action}Response xmlns:u="$service">$arguments</u:${action}Response></s:Body></s:Envelope>"""
    }

    /** Servizio descritto in modo minimo: i telefoni leggono solo nomi di azioni e argomenti. */
    fun scpd(actions: Map<String, List<String>>): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>""")
        actions.forEach { (action, arguments) ->
            append("<action><name>").append(action).append("</name><argumentList>")
            arguments.forEach { argument ->
                val direction = if (argument.startsWith("<")) "out" else "in"
                val name = argument.removePrefix("<")
                append("<argument><name>").append(name).append("</name><direction>").append(direction)
                append("</direction><relatedStateVariable>A_ARG_TYPE_").append(name).append("</relatedStateVariable></argument>")
            }
            append("</argumentList></action>")
        }
        append("</actionList><serviceStateTable>")
        actions.values.flatten().map { it.removePrefix("<") }.distinct().forEach { name ->
            append("""<stateVariable sendEvents="no"><name>A_ARG_TYPE_""").append(name).append("</name><dataType>string</dataType></stateVariable>")
        }
        append("</serviceStateTable></scpd>")
    }

    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    fun unescape(text: String): String = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
