package it.fotofesta.tv.hardware

import it.fotofesta.tv.network.LanHttp
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Stampa delle foto su una stampante di casa con IPP (AirPrint e Mopria parlano IPP sulla porta
 * 631), senza servizi di stampa installati sulla TV. Qui la codifica dei messaggi, senza Android.
 */
object Ipp {
    const val PORT = 631
    const val SERVICE_TYPE = "_ipp._tcp"

    const val PRINT_JOB = 0x0002
    const val GET_PRINTER_ATTRIBUTES = 0x000B

    private const val OPERATION_TAG = 0x01
    private const val JOB_TAG = 0x02
    private const val END_TAG = 0x03
    private const val INTEGER = 0x21
    private const val KEYWORD = 0x44
    private const val URI = 0x45
    private const val CHARSET = 0x47
    private const val LANGUAGE = 0x48
    private const val MIME = 0x49
    private const val NAME = 0x42

    /** Stampante trovata o scritta a mano: [path] è la risorsa ("ipp/print"). */
    data class Printer(val host: String, val port: Int = PORT, val path: String = "ipp/print", val name: String = host) {
        private val hostPart get() = if (':' in host) "[$host]" else host
        val httpUrl: String get() = "http://$hostPart:$port/${path.trimStart('/')}"
        val ippUri: String get() = "ipp://$hostPart:$port/${path.trimStart('/')}"
    }

    data class Attribute(val tag: Int, val name: String, val values: List<Any>)

    data class Response(val status: Int, val requestId: Int, val attributes: List<Attribute>) {
        val ok: Boolean get() = status in 0x0000..0x00FF
        fun strings(name: String): List<String> = attributes.filter { it.name == name }.flatMap { it.values }.filterIsInstance<String>()
    }

    fun getPrinterAttributes(printer: Printer, requestId: Int): ByteArray = encode(GET_PRINTER_ATTRIBUTES, requestId) {
        operation(printer)
        string(KEYWORD, "requested-attributes", "document-format-supported")
    }

    /** Lavoro di stampa con la foto in [document] (di solito JPEG) in [copies] copie. */
    fun printJob(printer: Printer, requestId: Int, jobName: String, format: String, document: ByteArray, copies: Int = 1): ByteArray {
        val head = encode(PRINT_JOB, requestId, end = false) {
            operation(printer)
            string(NAME, "job-name", jobName.take(60))
            string(MIME, "document-format", format)
            tag(JOB_TAG)
            integer("copies", copies.coerceIn(1, 10))
            // Foto a tutta pagina senza deformarla: "fill" taglia i bordi, "fit" lascia il margine.
            string(KEYWORD, "print-scaling", "fill")
            tag(END_TAG)
        }
        return head + document
    }

    /** Il formato da mandare: JPEG se la stampante lo accetta, altrimenti null (non stampabile da qui). */
    fun chooseFormat(supported: List<String>): String? = when {
        supported.any { it.equals("image/jpeg", ignoreCase = true) } -> "image/jpeg"
        supported.any { it.equals("application/octet-stream", ignoreCase = true) } -> "image/jpeg"
        else -> null
    }

    fun parse(bytes: ByteArray): Response {
        if (bytes.size < 8) throw IOException("Risposta IPP troppo corta")
        val buffer = ByteBuffer.wrap(bytes)
        buffer.short // versione
        val status = buffer.short.toInt() and 0xFFFF
        val requestId = buffer.int
        val attributes = ArrayList<Attribute>()
        var current: Attribute? = null
        while (buffer.hasRemaining()) {
            val tag = buffer.get().toInt() and 0xFF
            if (tag == END_TAG) break
            if (tag < 0x10) continue // inizio di un gruppo
            if (buffer.remaining() < 2) break
            val nameLength = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < nameLength + 2) break
            val name = ByteArray(nameLength).also { buffer.get(it) }.toString(Charsets.UTF_8)
            val valueLength = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < valueLength) break
            val raw = ByteArray(valueLength).also { buffer.get(it) }
            val value: Any = when (tag) {
                INTEGER, 0x23 -> if (raw.size == 4) ByteBuffer.wrap(raw).int else 0
                in 0x40..0x4F -> raw.toString(Charsets.UTF_8)
                else -> raw
            }
            if (nameLength == 0 && current != null) {
                // Valore in più dello stesso attributo.
                current = current.copy(values = current.values + value)
                attributes[attributes.lastIndex] = current
            } else {
                current = Attribute(tag, name, listOf(value))
                attributes += current
            }
        }
        return Response(status, requestId, attributes)
    }

    private class Writer(private val out: ByteArrayOutputStream) {
        fun tag(value: Int) = out.write(value)

        fun string(tag: Int, name: String, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            header(tag, name, bytes.size)
            out.write(bytes)
        }

        fun integer(name: String, value: Int) {
            header(INTEGER, name, 4)
            out.write(ByteBuffer.allocate(4).putInt(value).array())
        }

        fun operation(printer: Printer) {
            tag(OPERATION_TAG)
            string(CHARSET, "attributes-charset", "utf-8")
            string(LANGUAGE, "attributes-natural-language", "en")
            string(URI, "printer-uri", printer.ippUri)
            string(NAME, "requesting-user-name", "FotoFesta")
        }

        private fun header(tag: Int, name: String, length: Int) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.write(tag)
            out.write(nameBytes.size shr 8); out.write(nameBytes.size and 0xFF)
            out.write(nameBytes)
            out.write(length shr 8); out.write(length and 0xFF)
        }
    }

    private fun encode(operation: Int, requestId: Int, end: Boolean = true, body: Writer.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(2); out.write(0) // IPP 2.0
        out.write(operation shr 8); out.write(operation and 0xFF)
        out.write(ByteBuffer.allocate(4).putInt(requestId).array())
        Writer(out).body()
        if (end) out.write(END_TAG)
        return out.toByteArray()
    }
}

/** Manda i messaggi IPP alla stampante, solo nella rete di casa. */
class IppClient(private val http: LanHttp = LanHttp(connectTimeoutMs = 4_000, readTimeoutMs = 60_000)) {
    private var nextId = 1

    private fun call(printer: Ipp.Printer, body: ByteArray): Ipp.Response =
        http.post(printer.httpUrl, mapOf("Content-Type" to "application/ipp"), body).use { response ->
            if (response.status != 200) throw IOException("HTTP ${response.status}")
            Ipp.parse(response.bytes(256 * 1024))
        }

    /** Il formato con cui stampare le foto su [printer], o null se non ne accetta nessuno adatto. */
    @Synchronized
    fun format(printer: Ipp.Printer): String? =
        Ipp.chooseFormat(call(printer, Ipp.getPrinterAttributes(printer, nextId++)).strings("document-format-supported"))

    @Synchronized
    fun print(printer: Ipp.Printer, jpeg: ByteArray, jobName: String, copies: Int = 1): Boolean {
        val format = format(printer) ?: return false
        return call(printer, Ipp.printJob(printer, nextId++, jobName, format, jpeg, copies)).ok
    }
}
