package it.fotofesta.tv.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.PipeShare
import com.rapid7.client.dcerpc.Interface
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransport
import com.rapid7.helper.smbj.share.NamedPipe
import it.fotofesta.tv.data.NaturalOrder
import it.fotofesta.tv.files.FileBrowser
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SourceType
import it.fotofesta.tv.network.LanHttp
import java.io.Closeable
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Perché una cartella condivisa non si legge, per un messaggio chiaro sulla TV. */
class SmbException(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    enum class Reason {
        /** Computer spento, indirizzo sbagliato o fuori dalla rete di casa. */
        UNREACHABLE,

        /** Nome utente o password non accettati, oppure accesso ospite non permesso. */
        LOGIN,

        /** L'utente non ha il permesso di leggere questa condivisione o cartella. */
        DENIED,

        /** Condivisione o cartella che non c'è (più). */
        NOT_FOUND,

        /** Indirizzo fuori dalla rete di casa: la TV parla SMB solo con i computer della stessa rete. */
        NOT_LOCAL,
    }
}

/** Contenuto di una cartella: sottocartelle in ordine naturale, foto e video presenti. */
data class SmbListing(val folders: List<String>, val photos: Int, val videos: Int)

/**
 * Cartelle condivise (SMB 2 e 3) di computer e NAS della rete di casa: accesso, condivisioni,
 * cartelle, foto e video, letti al momento senza copiarli sulla TV. La connessione con il computer
 * resta aperta e si riapre da sola se il computer la chiude. Le chiamate sono bloccanti: vanno fatte
 * fuori dal thread principale.
 */
class SmbShares(
    private val imageExtensions: Set<String>,
    private val videoExtensions: Set<String> = FileBrowser.VIDEO_EXTENSIONS,
    connectTimeoutMs: Int = 8_000,
    responseTimeoutMs: Long = 30_000,
    /** Solo per i test: ammette anche un server sulla stessa macchina. */
    allowLoopback: Boolean = false,
) : Closeable {
    private val client = SMBClient(
        SmbConfig.builder()
            // MD4 e AES-CMAC per NTLM e SMB 3 non ci sono in tutte le versioni di Android.
            .withSecurityProvider(BCSecurityProvider())
            // Solo indirizzi della rete di casa, come per i server DLNA: un nome si risolve una volta
            // sola e ci si collega proprio all'indirizzo controllato.
            .withSocketFactory(LanSocketFactory(connectTimeoutMs, allowLoopback))
            .withTimeout(responseTimeoutMs, TimeUnit.MILLISECONDS)
            .withDfsEnabled(false)
            .build(),
    )
    private val logins = ConcurrentHashMap<String, SmbLogin>()

    // Usati sotto il lock di questo oggetto.
    private val sessions = HashMap<String, Session>()
    private val disks = HashMap<String, DiskShare>()

    /**
     * Prova l'accesso al computer e, se riesce, lo ricorda per le letture successive. Restituisce le
     * condivisioni; null se il computer non sa elencarle (si scrive allora il nome della condivisione).
     */
    fun login(server: SmbLocation, login: SmbLogin): List<String>? {
        val key = server.server
        val previous = logins[key]
        if (previous != login) disconnect(key)
        logins[key] = login
        try {
            session(server)
        } catch (failure: Exception) {
            if (previous == null) logins.remove(key) else logins[key] = previous
            disconnect(key)
            throw failure
        }
        return shares(server)
    }

    /** Ricorda l'accesso senza provarlo, per la cartella salvata: la prima lettura lo userà. */
    fun remember(server: SmbLocation, login: SmbLogin) {
        val key = server.server
        if (logins[key] != login) disconnect(key)
        logins[key] = login
    }

    fun loginFor(server: SmbLocation): SmbLogin? = logins[server.server]

    /** Condivisioni con file del computer, in ordine naturale; null se il computer non sa elencarle. */
    fun shares(server: SmbLocation): List<String>? = withSession(server) { session ->
        try {
            (session.connectShare("IPC$") as PipeShare).use { ipc ->
                NamedPipe(session, ipc, "srvsvc").use { pipe ->
                    val transport = SMBTransport(pipe)
                    transport.bind(Interface.SRVSVC_V3_0, Interface.NDR_32BIT_V2)
                    ServerService(transport).shares1
                        // Solo dischi (tipo 0): niente stampanti, IPC$ e condivisioni nascoste come C$.
                        .filter { it.type == 0 && !it.netName.endsWith("$") }
                        .map { it.netName }
                        .sortedWith(NaturalOrder)
                }
            }
        } catch (failure: Exception) {
            // Connessione caduta: ci pensa retrying. Altrimenti il computer non offre l'elenco
            // (alcuni NAS e router, o l'ospite senza permesso): si chiede il nome della condivisione.
            if (lostConnection(server, failure)) throw failure
            null
        } catch (_: LinkageError) {
            // Risposta DCE/RPC malformata: dcerpc la segnala con java.rmi, che su Android non c'è.
            null
        }
    }

    /** Sottocartelle e numero di foto e video di una cartella. */
    fun list(location: SmbLocation): SmbListing {
        require(location.share.isNotEmpty()) { "Serve una condivisione" }
        return withDisk(location) { disk ->
            val folders = mutableListOf<String>()
            var photos = 0
            var videos = 0
            for (entry in disk.list(location.smbPath)) {
                val name = entry.fileName
                if (skipped(name, entry.fileAttributes)) continue
                when {
                    entry.fileAttributes and DIRECTORY != 0L -> folders += name
                    extension(name) in imageExtensions -> photos++
                    extension(name) in videoExtensions -> videos++
                }
            }
            SmbListing(folders.sortedWith(NaturalOrder), photos, videos)
        }
    }

    /**
     * Foto e video della cartella e delle sottocartelle, al massimo [limit], in ordine di nome come
     * per i dischi USB. Una sottocartella che non si legge si salta. [checkCancelled] viene chiamato
     * a ogni cartella e può lanciare un'eccezione per interrompere.
     */
    fun scanPhotos(location: SmbLocation, limit: Int = MAX_PHOTOS, checkCancelled: () -> Unit = {}): List<PhotoItem> {
        require(location.share.isNotEmpty()) { "Serve una condivisione" }
        val queue = ArrayDeque<SmbLocation>().apply { add(location) }
        val found = mutableListOf<SmbLocation>()
        var folders = 0
        while (queue.isNotEmpty() && found.size < limit && folders < MAX_FOLDERS) {
            checkCancelled()
            val current = queue.removeFirst()
            folders++
            val entries = try {
                withDisk(current) { disk -> disk.list(current.smbPath) }
            } catch (failure: SmbException) {
                // La cartella scelta deve leggersi; una sottocartella senza permessi si salta.
                if (current == location || failure.reason == SmbException.Reason.UNREACHABLE) throw failure
                continue
            }
            for (entry in entries) {
                if (found.size >= limit) break
                val name = entry.fileName
                if (skipped(name, entry.fileAttributes)) continue
                val extension = extension(name)
                when {
                    entry.fileAttributes and DIRECTORY != 0L -> queue.addLast(current.child(name))
                    extension in imageExtensions || extension in videoExtensions -> found += current.child(name)
                }
            }
        }
        return found.sortedWith(compareBy(NaturalOrder) { it.name }).map { file ->
            PhotoItem(
                id = "smb:${file.browserPath}",
                title = file.name.substringBeforeLast('.').ifBlank { file.name },
                ref = PhotoRef.SmbPhoto(file.browserPath),
                source = SourceType.NETWORK,
                video = extension(file.name) in videoExtensions,
            )
        }
    }

    /** Apre un file per leggerlo a pezzi (video) o dall'inizio alla fine (foto). Va chiuso. */
    fun open(location: SmbLocation): SmbFile = withDisk(location) { disk ->
        val file = disk.openFile(
            location.smbPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        try {
            SmbFile(file, file.getFileInformation(FileStandardInformation::class.java).endOfFile)
        } catch (failure: Exception) {
            runCatching { file.close() }
            throw failure
        }
    }

    /** Chiude le connessioni: servono di nuovo solo quando si sceglie un'altra cartella condivisa. */
    @Synchronized
    fun disconnectAll() {
        sessions.keys.toList().forEach(::disconnect)
    }

    override fun close() {
        disconnectAll()
        runCatching { client.close() }
    }

    private fun <T> withSession(server: SmbLocation, block: (Session) -> T): T = retrying(server) { block(session(server)) }

    private fun <T> withDisk(location: SmbLocation, block: (DiskShare) -> T): T = retrying(location) { block(disk(location)) }

    /**
     * Esegue [block] e traduce gli errori. Se la connessione era già aperta e il computer l'ha
     * chiusa nel frattempo (NAS in standby, riavvio), la riapre e riprova una volta.
     */
    private fun <T> retrying(server: SmbLocation, block: () -> T): T {
        val wasOpen = synchronized(this) { sessions.containsKey(server.server) }
        return try {
            block()
        } catch (failure: Exception) {
            if (!wasOpen || !lostConnection(server, failure)) throw translate(failure)
            disconnect(server.server)
            try {
                block()
            } catch (again: Exception) {
                throw translate(again)
            }
        }
    }

    @Synchronized
    private fun session(server: SmbLocation): Session {
        val key = server.server
        sessions[key]?.let { session ->
            if (session.connection.isConnected) return session
            disconnect(key)
        }
        val login = logins[key] ?: SmbLogin()
        val connection = try {
            client.connect(server.host, server.port)
        } catch (failure: Exception) {
            val reason = if (causes(failure).any { it is NotLocalException }) SmbException.Reason.NOT_LOCAL else SmbException.Reason.UNREACHABLE
            throw SmbException(reason, failure)
        }
        val session = try {
            connection.authenticate(login.context())
        } catch (failure: Exception) {
            runCatching { connection.close() }
            // Ogni rifiuto all'accesso, anche "accesso negato" all'ospite, è un problema di credenziali.
            throw if (failure is SMBApiException) SmbException(SmbException.Reason.LOGIN, failure) else translate(failure)
        }
        sessions[key] = session
        return session
    }

    @Synchronized
    private fun disk(location: SmbLocation): DiskShare {
        val key = location.server + "/" + location.share.lowercase()
        disks[key]?.let { disk ->
            if (disk.isConnected) return disk
            // Condivisione chiusa dal computer (riavvio, standby): anche la connessione, che smbj
            // crede ancora aperta, non serve più. Si riparte da capo.
            disconnect(location.server)
        }
        val share = session(location).connectShare(location.share)
        if (share !is DiskShare) {
            runCatching { share.close() }
            throw SmbException(SmbException.Reason.NOT_FOUND)
        }
        disks[key] = share
        return share
    }

    @Synchronized
    private fun disconnect(server: String) {
        disks.keys.filter { it.startsWith("$server/") }.forEach { key -> disks.remove(key)?.let { runCatching { it.close() } } }
        sessions.remove(server)?.let { session ->
            runCatching { session.close() }
            runCatching { session.connection.close() }
        }
    }

    private fun SmbLogin.context(): AuthenticationContext =
        if (guest) AuthenticationContext.guest() else AuthenticationContext(user, password.toCharArray(), domain)

    private fun skipped(name: String, attributes: Long): Boolean =
        name == "." || name == ".." ||
            // File nascosti e di sistema, cestini e miniature dei NAS (@eaDir di Synology, #recycle…).
            name.first() in HIDDEN_PREFIXES || name.equals("System Volume Information", ignoreCase = true) ||
            attributes and (HIDDEN or SYSTEM) != 0L

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** La connessione con il computer è caduta (standby, riavvio, rete): riaprirla può bastare. */
    private fun lostConnection(server: SmbLocation, failure: Throwable): Boolean {
        val closed = synchronized(this) { sessions[server.server]?.connection?.isConnected == false }
        return closed || causes(failure).any { cause ->
            (cause as? SMBApiException)?.status in RECONNECT_STATUSES ||
                cause is TransportException || cause is SocketException || cause is EOFException ||
                // Errori di smbj senza stato SMB: condivisione o sessione già chiuse.
                (cause is SMBRuntimeException && cause !is SMBApiException)
        }
    }

    companion object {
        private const val MAX_PHOTOS = 10_000
        private const val MAX_FOLDERS = 2_000
        private val DIRECTORY = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
        private val HIDDEN = FileAttributes.FILE_ATTRIBUTE_HIDDEN.value
        private val SYSTEM = FileAttributes.FILE_ATTRIBUTE_SYSTEM.value
        private val HIDDEN_PREFIXES = setOf('.', '@', '#', '$', '~')

        private val LOGIN_STATUSES = setOf(
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_PASSWORD_EXPIRED,
            NtStatus.STATUS_ACCOUNT_DISABLED,
            NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
        )
        private val DENIED_STATUSES = setOf(NtStatus.STATUS_ACCESS_DENIED)
        private val NOT_FOUND_STATUSES = setOf(
            NtStatus.STATUS_BAD_NETWORK_NAME,
            NtStatus.STATUS_BAD_NETWORK_PATH,
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_OBJECT_NAME_INVALID,
            NtStatus.STATUS_NOT_FOUND,
            NtStatus.STATUS_PATH_NOT_COVERED,
        )
        private val RECONNECT_STATUSES = setOf(
            NtStatus.STATUS_USER_SESSION_DELETED,
            NtStatus.STATUS_NETWORK_NAME_DELETED,
            NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
            NtStatus.STATUS_CONNECTION_DISCONNECTED,
            NtStatus.STATUS_CONNECTION_RESET,
        )

        private fun causes(failure: Throwable): Sequence<Throwable> = generateSequence(failure) { it.cause?.takeIf { cause -> cause !== it } }.take(8)

        fun translate(failure: Throwable): SmbException {
            if (failure is SmbException) return failure
            val api = causes(failure).filterIsInstance<SMBApiException>().firstOrNull()
            val reason = when (api?.status) {
                in LOGIN_STATUSES -> SmbException.Reason.LOGIN
                in DENIED_STATUSES -> SmbException.Reason.DENIED
                in NOT_FOUND_STATUSES -> SmbException.Reason.NOT_FOUND
                else -> SmbException.Reason.UNREACHABLE
            }
            return SmbException(reason, failure)
        }
    }
}

/** File aperto su una cartella condivisa: si legge a pezzi da qualunque punto. */
class SmbFile internal constructor(private val file: com.hierynomus.smbj.share.File, val size: Long) : Closeable {
    /** Legge fino a [length] byte da [position]; -1 alla fine del file. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        return try {
            file.read(buffer, position, offset, minOf(length, MAX_READ))
        } catch (failure: Exception) {
            throw SmbShares.translate(failure)
        }
    }

    /** Il file dall'inizio; chiudendo lo stream si chiude anche il file. */
    fun inputStream(): InputStream = object : FilterInputStream(file.inputStream) {
        override fun close() {
            try {
                super.close()
            } finally {
                this@SmbFile.close()
            }
        }
    }

    override fun close() {
        runCatching { file.close() }
    }

    private companion object {
        const val MAX_READ = 1024 * 1024
    }
}

/**
 * smbj apre le connessioni senza un limite di tempo: verso un indirizzo che non risponde si
 * aspetterebbero minuti. Qui la connessione rinuncia dopo [timeoutMs].
 */
/** Indirizzo che non è della rete di casa. */
private class NotLocalException(host: String) : IOException("Indirizzo fuori dalla rete di casa: $host")

/**
 * Socket con un limite al tempo di connessione, aperti solo verso indirizzi della rete di casa
 * (privati IPv4, link-local, IPv6 locali): un server pubblico, anche dietro un nome, viene rifiutato.
 */
private class LanSocketFactory(private val timeoutMs: Int, private val allowLoopback: Boolean) : SocketFactory() {
    override fun createSocket(): Socket = Socket()

    override fun createSocket(host: String, port: Int): Socket = connect(InetSocketAddress(resolve(host), port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        connect(InetSocketAddress(resolve(host), port), InetSocketAddress(localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket = connect(InetSocketAddress(check(host), port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        connect(InetSocketAddress(check(address), port), InetSocketAddress(localAddress, localPort))

    /** Il primo indirizzo del nome che è della rete di casa; gli altri non si provano nemmeno. */
    private fun resolve(host: String): InetAddress =
        InetAddress.getAllByName(host).firstOrNull(::allowed) ?: throw NotLocalException(host)

    private fun check(address: InetAddress): InetAddress =
        if (allowed(address)) address else throw NotLocalException(address.hostAddress.orEmpty())

    private fun allowed(address: InetAddress): Boolean =
        LanHttp.isLanAddress(address) || (allowLoopback && address.isLoopbackAddress)

    private fun connect(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket {
        if (remote.isUnresolved) throw UnknownHostException(remote.hostString)
        val socket = Socket()
        try {
            local?.let(socket::bind)
            socket.connect(remote, timeoutMs)
            return socket
        } catch (failure: IOException) {
            runCatching { socket.close() }
            throw when (failure) {
                is SocketTimeoutException, is NoRouteToHostException -> ConnectException("${remote.hostString}: ${failure.message}")
                else -> failure
            }
        }
    }
}
