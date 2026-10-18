package it.fotofesta.tv.network

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/** Richiesta ricevuta dal server di prova. */
class FakeRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
}

/** Risposta del server di prova; con [chunked] il corpo va a blocchi, senza Content-Length. */
class FakeResponse(
    val status: Int = 200,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
    val chunked: Boolean = false,
)

/** Server HTTP minimo su 127.0.0.1 per provare LanHttp e NetworkMedia. */
class FakeLanServer(private val handler: (FakeRequest) -> FakeResponse) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val requests = mutableListOf<FakeRequest>()
    val base = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true) {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                thread(isDaemon = true) {
                    socket.use {
                        val input = BufferedInputStream(it.getInputStream())
                        val (method, path) = readLine(input).split(' ').let { parts -> parts[0] to parts[1] }
                        val headers = LinkedHashMap<String, String>()
                        while (true) {
                            val line = readLine(input)
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
                        }
                        val length = headers["content-length"]?.toInt() ?: 0
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val count = input.read(body, read, length - read)
                            if (count < 0) break
                            read += count
                        }
                        val request = FakeRequest(method, path, headers, body)
                        synchronized(requests) { requests += request }
                        val response = handler(request)
                        val out = ByteArrayOutputStream()
                        out.write("HTTP/1.1 ${response.status} X\r\n".toByteArray())
                        response.headers.forEach { (name, value) -> out.write("$name: $value\r\n".toByteArray()) }
                        if (response.chunked) {
                            out.write("Transfer-Encoding: chunked\r\n\r\n".toByteArray())
                            response.body.toList().chunked(7).forEach { chunk ->
                                out.write("${Integer.toHexString(chunk.size)};x=1\r\n".toByteArray())
                                out.write(chunk.toByteArray())
                                out.write("\r\n".toByteArray())
                            }
                            out.write("0\r\nX-Fine: si\r\n\r\n".toByteArray())
                        } else {
                            out.write("Content-Length: ${response.body.size}\r\n\r\n".toByteArray())
                            out.write(response.body)
                        }
                        it.getOutputStream().write(out.toByteArray())
                        it.getOutputStream().flush()
                    }
                }
            }
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0 || byte == '\n'.code) break
            if (byte != '\r'.code) line.append(byte.toChar())
        }
        return line.toString()
    }

    override fun close() = server.close()
}
