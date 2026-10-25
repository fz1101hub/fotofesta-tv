package it.fotofesta.tv.network

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server FTP minimo per le fotocamere con il Wi‑Fi (Canon, Nikon, Sony, Fujifilm sanno mandare
 * ogni scatto a un server FTP): ogni file ricevuto va a [onFile], che lo mette nella festa. Si
 * accettano solo caricamenti, in una cartella virtuale vuota; nessun file della TV è raggiungibile.
 * Modalità passiva (PASV/EPSV) e attiva (PORT, solo verso lo stesso indirizzo della fotocamera).
 */
class FtpServer(
    private val user: String,
    private val password: () -> String,
    private val onFile: (name: String, bytes: ByteArray) -> Unit,
    private val port: Int = DEFAULT_PORT,
    private val maxFileBytes: Int = MAX_FILE_BYTES,
    /** Solo per i test: accetta anche 127.0.0.1. */
    private val allowLoopback: Boolean = false,
) {
    @Volatile private var server: ServerSocket? = null
    private val sessions = AtomicInteger(0)
    private val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "ftp").apply { isDaemon = true } }

    val localPort: Int get() = server?.localPort ?: -1
    val running: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        server = socket
        pool.execute { acceptLoop(socket) }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            val address = client.inetAddress
            if (!(LanHttp.isLanAddress(address) || (allowLoopback && address.isLoopbackAddress)) || sessions.get() >= MAX_SESSIONS) {
                runCatching { client.close() }
                continue
            }
            sessions.incrementAndGet()
            pool.execute {
                try {
                    client.use { Session(it).run() }
                } catch (_: IOException) {
                } finally {
                    sessions.decrementAndGet()
                }
            }
        }
    }

    private inner class Session(private val control: Socket) {
        private val reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.UTF_8))
        private val writer: OutputStream = control.getOutputStream()
        private var userOk = false
        private var loggedIn = false
        private var failures = 0
        private var passive: ServerSocket? = null
        private var active: InetSocketAddress? = null
        private var directory = "/"

        fun run() {
            control.soTimeout = IDLE_TIMEOUT_MS
            reply(220, "FotoFesta")
            try {
                while (true) {
                    val line = readLine() ?: return
                    val verb = line.substringBefore(' ').uppercase()
                    val argument = line.substringAfter(' ', "").trim()
                    if (!handle(verb, argument)) return
                }
            } finally {
                runCatching { passive?.close() }
            }
        }

        /** false per chiudere la connessione. */
        private fun handle(verb: String, argument: String): Boolean {
            when (verb) {
                "USER" -> {
                    userOk = argument == user
                    reply(331, "Password")
                    return true
                }
                "PASS" -> {
                    if (userOk && argument == password() && password().isNotEmpty()) {
                        loggedIn = true
                        reply(230, "OK")
                    } else {
                        failures++
                        Thread.sleep(FAILURE_DELAY_MS)
                        reply(530, "Login incorrect")
                        if (failures >= MAX_FAILURES) return false
                    }
                    return true
                }
                "QUIT" -> {
                    reply(221, "Bye")
                    return false
                }
                "SYST" -> return reply(215, "UNIX Type: L8")
                "FEAT" -> {
                    send("211-Features:\r\n PASV\r\n EPSV\r\n UTF8\r\n211 End\r\n")
                    return true
                }
                "OPTS", "NOOP", "MODE", "STRU", "TYPE" -> return reply(200, "OK")
                "AUTH", "PBSZ", "PROT" -> return reply(502, "Not implemented")
            }
            if (!loggedIn) return reply(530, "Please login")
            when (verb) {
                "PWD", "XPWD" -> return reply(257, "\"$directory\"")
                "CWD", "XCWD" -> {
                    directory = virtualPath(argument)
                    return reply(250, "OK")
                }
                "CDUP" -> {
                    directory = "/"
                    return reply(250, "OK")
                }
                "MKD", "XMKD" -> return reply(257, "\"${virtualPath(argument)}\" created")
                "ALLO" -> return reply(202, "OK")
                "DELE", "RMD" -> return reply(250, "OK")
                "SIZE", "MDTM", "RETR" -> return reply(550, "Not available")
                "PASV" -> return openPassive(extended = false)
                "EPSV" -> return openPassive(extended = true)
                "PORT" -> return openActive(argument)
                "LIST", "NLST", "MLSD" -> {
                    val data = dataConnection() ?: return reply(425, "No data connection")
                    reply(150, "Listing")
                    runCatching { data.close() }
                    return reply(226, "Done")
                }
                "STOR", "APPE", "STOU" -> {
                    val name = argument.substringAfterLast('/').ifEmpty { "foto.jpg" }
                    val data = dataConnection() ?: return reply(425, "No data connection")
                    reply(150, "Ready")
                    val bytes = data.use { receive(it) }
                    if (bytes == null) return reply(552, "File too large")
                    runCatching { onFile(name, bytes) }
                    return reply(226, "Received")
                }
            }
            return reply(502, "Not implemented")
        }

        private fun virtualPath(argument: String): String =
            if (argument.startsWith("/")) argument.take(200) else (directory.trimEnd('/') + "/" + argument).take(200)

        private fun openPassive(extended: Boolean): Boolean {
            runCatching { passive?.close() }
            active = null
            val local = control.localAddress
            val socket = ServerSocket(0, 1, local)
            socket.soTimeout = DATA_TIMEOUT_MS
            passive = socket
            return if (extended) {
                reply(229, "Entering Extended Passive Mode (|||${socket.localPort}|)")
            } else {
                val ipv4 = (local as? Inet4Address)?.address ?: return reply(522, "Use EPSV")
                val parts = ipv4.joinToString(",") { (it.toInt() and 0xFF).toString() }
                reply(227, "Entering Passive Mode ($parts,${socket.localPort shr 8},${socket.localPort and 0xFF})")
            }
        }

        private fun openActive(argument: String): Boolean {
            val numbers = argument.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (numbers.size != 6 || numbers.any { it !in 0..255 }) return reply(501, "Bad PORT")
            val address = InetAddress.getByAddress(numbers.take(4).map { it.toByte() }.toByteArray())
            // Mai verso un altro computer: solo verso la fotocamera che ha aperto la connessione.
            if (address != control.inetAddress) return reply(504, "Only to yourself")
            runCatching { passive?.close() }
            passive = null
            active = InetSocketAddress(address, numbers[4] * 256 + numbers[5])
            return reply(200, "OK")
        }

        private fun dataConnection(): Socket? = try {
            passive?.let { listening ->
                passive = null
                listening.use { it.accept() }.also { accepted ->
                    if (accepted.inetAddress != control.inetAddress) {
                        accepted.close()
                        return null
                    }
                }
            } ?: active?.let { target ->
                active = null
                Socket().apply { connect(target, DATA_TIMEOUT_MS) }
            }
        } catch (_: IOException) {
            null
        }?.apply { soTimeout = DATA_TIMEOUT_MS }

        /** Il file intero, o null se supera [maxFileBytes] (il resto si legge e si butta). */
        private fun receive(data: Socket): ByteArray? {
            val input = data.getInputStream()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var tooLarge = false
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (_: SocketException) {
                    -1
                }
                if (read < 0) break
                if (!tooLarge && out.size() + read > maxFileBytes) {
                    tooLarge = true
                    out.reset()
                }
                if (!tooLarge) out.write(buffer, 0, read)
            }
            return if (tooLarge) null else out.toByteArray()
        }

        private fun readLine(): String? {
            val line = reader.readLine() ?: return null
            return line.take(MAX_LINE)
        }

        private fun reply(code: Int, text: String): Boolean {
            send("$code $text\r\n")
            return true
        }

        private fun send(text: String) {
            writer.write(text.toByteArray(Charsets.UTF_8))
            writer.flush()
        }
    }

    companion object {
        const val DEFAULT_PORT = 2121
        const val MAX_FILE_BYTES = 25 * 1024 * 1024
        private const val MAX_SESSIONS = 3
        private const val MAX_FAILURES = 3
        private const val FAILURE_DELAY_MS = 1_000L
        private const val MAX_LINE = 1_024
        private const val IDLE_TIMEOUT_MS = 5 * 60_000
        private const val DATA_TIMEOUT_MS = 30_000
    }
}
