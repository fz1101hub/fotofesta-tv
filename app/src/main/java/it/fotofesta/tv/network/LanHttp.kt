package it.fotofesta.tv.network

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException

/** Indirizzo fuori dalla rete di casa, o non HTTP: l'app non ci si collega in chiaro. */
class NotLanAddressException(url: String) : IOException("Indirizzo fuori dalla rete locale: $url")

/** Risposta letta da un server della rete di casa; chiuderla chiude la connessione. */
class LanResponse internal constructor(
    val status: Int,
    /** Intestazioni con il nome in minuscolo. */
    val headers: Map<String, String>,
    /** Corpo già decodificato (lunghezza fissa, a blocchi o fino alla chiusura). */
    val body: InputStream,
) : Closeable {
    val isSuccessful: Boolean get() = status in 200..299

    /** Il corpo intero, che non deve superare [limit] byte. */
    fun bytes(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = body.read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) throw IOException("Risposta troppo grande")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    override fun close() = body.close()
}

/**
 * Client HTTP minimo per i server multimediali della rete di casa (DLNA), che parlano solo HTTP in
 * chiaro. L'app vieta l'HTTP in chiaro con la configurazione di rete; questo client lo permette
 * soltanto verso indirizzi privati (192.168.x.x, 10.x.x.x, fd00::…), mai verso Internet.
 */
class LanHttp(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 20_000,
    /** Solo per i test: permette 127.0.0.1. */
    private val allowLoopback: Boolean = false,
) {
    fun get(url: String, headers: Map<String, String> = emptyMap()): LanResponse = request("GET", url, headers, null)

    fun post(url: String, headers: Map<String, String>, body: ByteArray): LanResponse = request("POST", url, headers, body)

    /** L'indirizzo è nella rete di casa e quindi raggiungibile da questo client. */
    fun accepts(url: String): Boolean = runCatching { target(url) }.isSuccess

    private fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): LanResponse {
        var currentUrl = url
        var currentMethod = method
        var currentBody = body
        repeat(MAX_REDIRECTS + 1) {
            val response = send(currentMethod, currentUrl, headers, currentBody)
            val location = response.headers["location"]
            if (response.status !in REDIRECTS || location == null) return response
            response.close()
            currentUrl = try {
                URI(currentUrl).resolve(location.trim()).toString()
            } catch (_: URISyntaxException) {
                throw IOException("Reindirizzamento non valido")
            } catch (_: IllegalArgumentException) {
                throw IOException("Reindirizzamento non valido")
            }
            if (response.status == 303) {
                currentMethod = "GET"
                currentBody = null
            }
        }
        throw IOException("Troppi reindirizzamenti")
    }

    private class Target(val address: InetAddress, val port: Int, val hostHeader: String, val path: String)

    private fun target(url: String): Target {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            throw NotLanAddressException(url)
        }
        val host = uri.host ?: throw NotLanAddressException(url)
        if (!uri.scheme.equals("http", ignoreCase = true) || uri.rawUserInfo != null) throw NotLanAddressException(url)
        val port = if (uri.port == -1) 80 else uri.port
        // Gli indirizzi dei server DLNA sono quasi sempre numerici: niente DNS. Un nome si risolve una
        // volta sola e ci si collega proprio all'indirizzo controllato.
        val address = InetAddress.getAllByName(host.removePrefix("[").removeSuffix("]"))
            .firstOrNull { isLanAddress(it) || (allowLoopback && it.isLoopbackAddress) }
            ?: throw NotLanAddressException(url)
        val hostHeader = hostHeader(host, port)
        val path = (uri.rawPath?.ifEmpty { null } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        if (path.any { it.isWhitespace() || it.code < 0x20 }) throw NotLanAddressException(url)
        return Target(address, port, hostHeader, path)
    }

    private fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): LanResponse {
        val target = target(url)
        val socket = Socket()
        try {
            socket.soTimeout = readTimeoutMs
            socket.connect(InetSocketAddress(target.address, target.port), connectTimeoutMs)
            val head = buildString {
                append("$method ${target.path} HTTP/1.1\r\n")
                append("Host: ${target.hostHeader}\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n")
                headers.forEach { (name, value) ->
                    require(name.none { it == '\r' || it == '\n' || it == ':' } && value.none { it == '\r' || it == '\n' })
                    append("$name: $value\r\n")
                }
                if (body != null) append("Content-Length: ${body.size}\r\n")
                append("\r\n")
            }
            val output = socket.getOutputStream()
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            if (body != null) output.write(body)
            output.flush()

            val input = BufferedInputStream(socket.getInputStream())
            var status: Int
            var responseHeaders: Map<String, String>
            // Le risposte 1xx ("100 Continue") precedono quella vera.
            do {
                status = parseStatus(readLine(input))
                responseHeaders = readHeaders(input)
            } while (status in 100..199)
            val raw = SocketClosingStream(input, socket)
            val length = responseHeaders["content-length"]?.trim()?.toLongOrNull()
            val stream: InputStream = when {
                method == "HEAD" || status == 204 || status == 304 -> FixedLengthStream(raw, 0)
                responseHeaders["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> ChunkedStream(raw)
                length != null && length >= 0 -> FixedLengthStream(raw, length)
                else -> raw
            }
            return LanResponse(status, responseHeaders, stream)
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }
    }

    companion object {
        private const val USER_AGENT = "FotoFesta-TV UPnP/1.0 DLNADOC/1.50"
        private const val MAX_REDIRECTS = 3
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 100
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /**
         * Intestazione Host com'è scritto l'indirizzo: un nome resta un nome anche se risolve in IPv6,
         * e un indirizzo IPv6 numerico tiene le sue parentesi.
         */
        internal fun hostHeader(host: String, port: Int): String = host + if (port != 80) ":$port" else ""

        /** Indirizzi privati IPv4, link-local e IPv6 locali (fc00::/7). */
        fun isLanAddress(address: InetAddress): Boolean = when (address) {
            is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress
            is Inet6Address -> address.isLinkLocalAddress || address.isSiteLocalAddress ||
                (address.address[0].toInt() and 0xfe) == 0xfc
            else -> false
        }

        internal fun readLine(input: InputStream): String {
            val line = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) {
                    if (line.isEmpty()) throw IOException("Connessione chiusa")
                    break
                }
                if (byte == '\n'.code) break
                if (byte != '\r'.code) line.append(byte.toChar())
                if (line.length > MAX_LINE) throw IOException("Riga troppo lunga")
            }
            return line.toString()
        }

        private fun parseStatus(line: String): Int {
            val parts = line.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("Risposta non HTTP")
            return parts[1].toIntOrNull() ?: throw IOException("Risposta non HTTP")
        }

        internal fun readHeaders(input: InputStream): Map<String, String> {
            val headers = LinkedHashMap<String, String>()
            repeat(MAX_HEADERS + 1) {
                val line = readLine(input)
                if (line.isEmpty()) return headers
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[name] = headers[name]?.let { "$it, $value" } ?: value
                }
            }
            throw IOException("Troppe intestazioni")
        }
    }
}

/** Chiude anche la connessione quando si chiude il corpo. */
private class SocketClosingStream(input: InputStream, private val socket: Socket) : FilterInputStream(input) {
    override fun close() {
        try {
            super.close()
        } finally {
            socket.close()
        }
    }
}

/** Corpo con Content-Length: finisce dopo [remaining] byte; se la connessione cade prima è un errore. */
private class FixedLengthStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val byte = super.read()
        if (byte < 0) throw IOException("Risposta interrotta")
        remaining--
        return byte
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val read = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read < 0) throw IOException("Risposta interrotta")
        remaining -= read
        return read
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, remaining))
        remaining -= skipped
        return skipped
    }

    override fun markSupported() = false
}

/** Corpo "Transfer-Encoding: chunked". */
private class ChunkedStream(input: InputStream) : FilterInputStream(input) {
    private var remaining = 0L
    private var finished = false

    private fun nextChunk(): Boolean {
        if (finished) return false
        if (remaining > 0) return true
        val line = LanHttp.readLine(`in`).substringBefore(';').trim()
        val size = line.toLongOrNull(16) ?: throw IOException("Blocco non valido")
        if (size < 0) throw IOException("Blocco non valido")
        if (size == 0L) {
            finished = true
            // Intestazioni finali, poi la riga vuota.
            LanHttp.readHeaders(`in`)
            return false
        }
        remaining = size
        return true
    }

    override fun read(): Int {
        val buffer = ByteArray(1)
        return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!nextChunk()) return -1
        val read = `in`.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read < 0) throw IOException("Risposta interrotta")
        remaining -= read
        // Fine del blocco: segue un a capo.
        if (remaining == 0L) LanHttp.readLine(`in`).let { if (it.isNotEmpty()) throw IOException("Blocco non valido") }
        return read
    }

    override fun available(): Int = if (finished) 0 else minOf(super.available().toLong(), remaining).toInt()

    override fun skip(n: Long): Long {
        val buffer = ByteArray(minOf(n, 8_192L).toInt().coerceAtLeast(1))
        val read = read(buffer, 0, buffer.size)
        return if (read < 0) 0 else read.toLong()
    }

    override fun markSupported() = false
}
