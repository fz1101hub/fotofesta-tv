package it.fotofesta.tv.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Ricerca dei server multimediali (DLNA/UPnP) della rete di casa con SSDP: una domanda in multicast
 * a cui i server rispondono direttamente alla TV con l'indirizzo della loro descrizione.
 */
object Ssdp {
    const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
    private const val ADDRESS = "239.255.255.250"
    private const val PORT = 1900
    private const val MX = 2

    /** Risposta di un server: indirizzo della descrizione e identificativo del dispositivo. */
    data class Answer(val location: String, val udn: String)

    fun searchMessage(target: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $ADDRESS:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $MX\r\n" +
            "ST: $target\r\n" +
            "USER-AGENT: Android UPnP/1.1 FotoFesta-TV/1.0\r\n" +
            "\r\n"

    /** Risposta a M-SEARCH ("HTTP/1.1 200 OK" con LOCATION e USN); null se non è una risposta valida. */
    fun parseAnswer(text: String): Answer? {
        val lines = text.split("\r\n", "\n")
        if (!lines.first().trim().uppercase().let { it.startsWith("HTTP/1.1 200") || it.startsWith("HTTP/1.0 200") }) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val location = headers["location"]?.takeIf { it.startsWith("http://", ignoreCase = true) } ?: return null
        val udn = headers["usn"]?.substringBefore("::")?.takeIf { it.isNotBlank() } ?: return null
        return Answer(location, udn)
    }

    /**
     * Chiede due volte, perché UDP può perdere pacchetti, e raccoglie le risposte per [timeoutMs].
     * Le risposte arrivano in unicast alla porta della domanda: non serve ascoltare il multicast.
     */
    fun search(timeoutMs: Int = 3_000, targets: List<String> = listOf(CONTENT_DIRECTORY, MEDIA_SERVER)): List<Answer> {
        val answers = LinkedHashMap<String, Answer>()
        DatagramSocket().use { socket ->
            val group = InetAddress.getByName(ADDRESS)
            repeat(2) {
                for (target in targets) {
                    val message = searchMessage(target).toByteArray(Charsets.US_ASCII)
                    runCatching { socket.send(DatagramPacket(message, message.size, group, PORT)) }
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4 * 1024)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val answer = parseAnswer(String(packet.data, packet.offset, packet.length, Charsets.UTF_8)) ?: continue
                answers.putIfAbsent(answer.location, answer)
            }
        }
        return answers.values.toList()
    }
}
