package it.fotofesta.tv.guests

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.webkit.MimeTypeMap
import android.util.Log
import it.fotofesta.tv.data.KeystoreStrings
import it.fotofesta.tv.data.SettingsRepository
import it.fotofesta.tv.media.BackgroundMusicPlayer
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.GuestProblem
import it.fotofesta.tv.network.PartyBeacon
import it.fotofesta.tv.tv.DlnaRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Festa con le foto degli invitati: tiene acceso il server sulla rete di casa mentre l'app è in
 * primo piano, segue i cambi di rete e ricorda le impostazioni.
 *
 * Tutte le operazioni passano da un unico thread, così avvio, arresto e cambi di rete non si
 * sovrappongono. Lo stato da mostrare è in [state]; le foto sono in [store].
 */
class GuestParty(
    private val context: Context,
    val store: GuestPhotoStore,
    /** Gioco con i telefoni, servito dalla stessa pagina della festa. */
    private val phoneGame: PhoneGame? = null,
    /** Reazioni e dediche dei telefoni: si cancellano con le foto a fine festa. */
    private val hub: PartyHub? = null,
    /** Regia dal telefono: PIN nuovo a ogni festa. */
    private val host: HostRemote? = null,
    /** Giochi con i telefoni: le foto mandate durante un gioco fotografico vengono etichettate. */
    private val play: PartyPlay? = null,
) {
    data class State(
        val active: Boolean = false,
        val url: String? = null,
        val problem: GuestProblem? = null,
        val approval: Boolean = false,
        val qrInSlideshow: Boolean = true,
        val wifi: WifiNetwork? = null,
        /** Rete creata dalla TV (hotspot): se c'è, il QR della Wi‑Fi mostra questa. */
        val hotspot: WifiNetwork? = null,
    )

    // Esclusi dal backup: valgono solo per questa TV e per la festa in corso.
    private val prefs = context.getSharedPreferences("guest_party", Context.MODE_PRIVATE)
    private val secrets = KeystoreStrings(prefs, "fotofesta_guest_wifi_v1")
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "guest-party").apply { isDaemon = true }
    }

    private val _state = MutableStateFlow(
        State(
            active = prefs.getBoolean(KEY_ACTIVE, false),
            approval = prefs.getBoolean(KEY_APPROVAL, false),
            qrInSlideshow = prefs.getBoolean(KEY_QR_IN_SLIDESHOW, true),
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** Vero quando le foto di una festa interrotta sono state riprese (o cancellate) all'avvio. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    // Usati solo dal thread `worker`.
    private var foreground = false
    private var server: GuestUploadServer? = null
    private var serverAddress: InetAddress? = null
    private val networks = mutableMapOf<Network, Inet4Address?>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Cresce a ogni registrazione: le notifiche di una registrazione precedente vengono ignorate. */
    private var networkGeneration = 0
    private var retry: ScheduledFuture<*>? = null
    /** Indirizzo della TV sulla rete che ha creato lei (hotspot): ha la precedenza sulla rete di casa. */
    private var hotspotAddress: Inet4Address? = null
    /** Schermo DLNA per i telefoni, acceso con il server se l'impostazione lo chiede. */
    private var dlna: DlnaRenderer? = null
    private var dlnaEnabled = false
    private var dlnaAddress: InetAddress? = null
    @Volatile var dlnaFactory: (() -> DlnaRenderer)? = null
    /** Annuncio della festa per gli schermi aggiuntivi delle altre stanze. */
    private val beacon = PartyBeacon(context)
    /** Ogni foto salvata, dai thread del server: serve alla copia di sicurezza nella galleria. */
    @Volatile var onPhotoSaved: ((bytes: ByteArray, sender: String?) -> Unit)? = null
    /** Silent disco accesa: il brano che sta suonando la TV (a volume zero), letto dai thread del server. */
    @Volatile var discoSource: (() -> BackgroundMusicPlayer.NowPlaying?)? = null
    /** Muro di telefoni: i telefoni sul tavolo mostrano insieme una foto della parete. */
    @Volatile var phoneWall: PhoneWall? = null
    private var hubSave: ScheduledFuture<*>? = null
    private val hubFile get() = java.io.File(context.noBackupFilesDir, HUB_FILE)

    private val template by lazy {
        context.assets.open("guest/index.html").use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Ingresso e parete delle foto: solo foto approvate degli invitati, mai la raccolta della TV. */
    private val pages = object : PartyExtras {
        private val door by lazy { asset("guest/door.html") }
        private val wall by lazy { asset("guest/wall.html") }

        override fun doorPage(): String = door

        override fun wallPage(): String = wall

        override fun wallState(): String {
            val approved = store.photos.value.filter { it.approved }.sortedByDescending { it.receivedAtMillis }
            val title = runCatching { context.getString(SettingsRepository(context).load().template.titleRes) }.getOrDefault("")
            return buildString {
                append("""{"title":""").append(GuestUploadServer.jsonString(title))
                append(""","count":""").append(approved.size)
                append(""","photos":[""")
                approved.take(WALL_PHOTOS).forEachIndexed { index, photo ->
                    if (index > 0) append(',')
                    append("""{"id":""").append(GuestUploadServer.jsonString(photo.id))
                    append(""","sender":""").append(photo.sender?.let { GuestUploadServer.jsonString(it) } ?: "null").append('}')
                }
                append("""],"messages":[""")
                hub?.messages()?.takeLast(WALL_MESSAGES)?.forEachIndexed { index, message ->
                    if (index > 0) append(',')
                    append("""{"text":""").append(GuestUploadServer.jsonString(message.text))
                    append(""","name":""").append(message.name?.let { GuestUploadServer.jsonString(it) } ?: "null").append('}')
                }
                append("""],"announcement":""").append(hub?.announcement()?.text?.let { GuestUploadServer.jsonString(it) } ?: "null")
                append('}')
            }
        }

        override fun wallPhoto(id: String): ByteArray? =
            id.takeIf { wanted -> store.photos.value.any { it.id == wanted && it.approved } }?.let(store::read)

        override fun discoJson(): String {
            val source = discoSource ?: return "null"
            // Accesa ma la musica non è ancora partita: i telefoni aspettano il brano.
            val now = source() ?: return """{"on":true,"track":null,"position":0,"playing":false}"""
            return """{"on":true,"track":${GuestUploadServer.jsonString(discoId(now.uri))},""" +
                """"position":${now.positionMs},"playing":${now.playing}}"""
        }

        /** Solo il brano in corso: l'id è un'impronta dell'indirizzo, che ai telefoni non arriva mai. */
        override fun discoTrack(id: String): DiscoTrack? {
            val uri = discoSource?.invoke()?.uri?.takeIf { discoId(it) == id } ?: return null
            val parsed = Uri.parse(uri)
            val length = runCatching {
                context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { file ->
                    file.length.takeIf { it >= 0 } ?: file.parcelFileDescriptor.statSize
                }
            }.getOrNull()?.takeIf { it > 0 } ?: return null
            val type = context.contentResolver.getType(parsed)?.takeIf { it.startsWith("audio/") }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.substringAfterLast('/').substringAfterLast('.', "").lowercase())
                    ?.takeIf { it.startsWith("audio/") }
                ?: "audio/mpeg"
            return DiscoTrack(length, type) { offset ->
                val file = context.contentResolver.openAssetFileDescriptor(parsed, "r") ?: throw IOException("track")
                file.createInputStream().also { input ->
                    var left = offset
                    while (left > 0) {
                        val skipped = input.skip(left)
                        if (skipped <= 0) break
                        left -= skipped
                    }
                }
            }
        }

        override fun phoneWallJson(player: String?): String = phoneWall?.phoneJson(player) ?: "null"

        override fun phoneWallAction(form: Map<String, String>): Pair<Int, String>? = phoneWall?.handle(form)

        private fun asset(path: String) = context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun discoId(uri: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }

    /** All'avvio del processo: riprende la festa interrotta da poco, altrimenti cancella le foto rimaste. */
    fun initialize() = worker.execute {
        val wifi = loadWifi()
        _state.update { it.copy(wifi = wifi) }
        if (!_state.value.active) {
            store.clear()
        } else {
            store.restore(IDLE_LIMIT_MS)
            val newest = store.photos.value.maxOfOrNull { it.receivedAtMillis } ?: 0L
            val lastActivity = max(prefs.getLong(KEY_STARTED_AT, 0L), newest)
            if (System.currentTimeMillis() - lastActivity > IDLE_LIMIT_MS) {
                endParty()
            } else {
                // Dediche, nomi, reazioni e scatti del rullino della festa interrotta.
                runCatching { hubFile.readText() }.getOrNull()?.let { hub?.restore(it) }
            }
        }
        _ready.value = true
    }

    /** Inizia a ricevere foto: il QR resta lo stesso finché la festa non finisce. */
    fun start() = worker.execute {
        if (_state.value.active) return@execute
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_SECRET, newSecret())
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .apply()
        host?.reset()
        _state.update { it.copy(active = true) }
        if (foreground) serve()
    }

    /** Fine della festa: il server si ferma e tutte le foto ricevute vengono cancellate. */
    fun end() = worker.execute { endParty() }

    fun setApproval(enabled: Boolean) = worker.execute {
        prefs.edit().putBoolean(KEY_APPROVAL, enabled).apply()
        _state.update { it.copy(approval = enabled) }
        // Senza approvazione le foto in attesa non avrebbero più modo di comparire.
        if (!enabled) store.photos.value.filterNot { it.approved }.forEach { store.approve(it.id) }
    }

    fun setQrInSlideshow(enabled: Boolean) = worker.execute {
        prefs.edit().putBoolean(KEY_QR_IN_SLIDESHOW, enabled).apply()
        _state.update { it.copy(qrInSlideshow = enabled) }
    }

    fun saveWifi(network: WifiNetwork?) = worker.execute {
        try {
            secrets.put(KEY_WIFI_PASSWORD, network?.password)
            prefs.edit().putString(KEY_WIFI_SSID, network?.ssid).apply()
        } catch (failure: Exception) {
            // Keystore non disponibile: la rete vale solo finché l'app resta aperta, e non resta
            // salvato un nome senza password che al riavvio sembrerebbe una rete aperta.
            Log.w(TAG, "Password Wi-Fi non salvata", failure)
            prefs.edit().remove(KEY_WIFI_SSID).remove(KEY_WIFI_PASSWORD).apply()
        }
        _state.update { it.copy(wifi = network) }
    }

    /** Salva tra poco lo stato delle attività (più cambi ravvicinati, una sola scrittura). */
    fun saveHubSoon() = worker.execute {
        val party = hub ?: return@execute
        if (!_state.value.active || hubSave?.isDone == false) return@execute
        hubSave = worker.schedule(Runnable {
            if (_state.value.active) runCatching { hubFile.writeText(party.snapshot()) }
        }, HUB_SAVE_DELAY_SECONDS, TimeUnit.SECONDS)
    }

    /** Foto di un telefono simulato durante la prova generale. */
    fun addRehearsalPhoto(bytes: ByteArray, sender: String) = worker.execute {
        if (_state.value.active) store.add(bytes, sender, approved = !_state.value.approval)
    }

    /**
     * Foto arrivata senza la pagina della festa: dallo schermo DLNA o dalla telecamera del vecchio
     * telefono. Vale come le altre (approvazione, copie di sicurezza) ma non conta nei limiti a testa.
     */
    fun addPhoto(bytes: ByteArray, sender: String?) = worker.execute {
        if (!_state.value.active || !isDisplayable(bytes)) return@execute
        if (store.add(bytes, sender, approved = !_state.value.approval) is GuestPhotoStore.AddResult.Added) {
            onPhotoSaved?.invoke(bytes, sender)
        }
    }

    /** Rete creata dalla TV: il server passa sul suo indirizzo e il QR della Wi‑Fi la mostra. */
    fun useHotspot(network: WifiNetwork?, address: Inet4Address?) = worker.execute {
        hotspotAddress = address.takeIf { network != null }
        _state.update { it.copy(hotspot = network?.takeIf { address != null }) }
        if (serving()) serve()
    }

    /** Accende o spegne lo schermo DLNA; parte davvero solo durante la festa in primo piano. */
    fun setDlna(enabled: Boolean) = worker.execute {
        dlnaEnabled = enabled
        syncDlna()
    }

    fun approve(id: String) = worker.execute { store.approve(id) }

    fun approveAll() = worker.execute { store.photos.value.filterNot { it.approved }.forEach { store.approve(it.id) } }

    fun reject(id: String) = worker.execute { store.remove(id) }

    fun onForeground() = worker.execute {
        foreground = true
        if (_state.value.active) serve()
    }

    /** In background il server si ferma e le foto passano su disco: Android potrebbe chiudere l'app. */
    fun onBackground() = worker.execute {
        foreground = false
        stopServing()
        store.spillAll()
    }

    fun onLowMemory() = worker.execute { store.spillAll() }

    private fun serving() = foreground && _state.value.active

    private fun endParty() {
        stopServing()
        store.clear()
        hub?.clear()
        play?.clear()
        host?.reset()
        hubSave?.cancel(false)
        hubSave = null
        hubFile.delete()
        prefs.edit().putBoolean(KEY_ACTIVE, false).remove(KEY_SECRET).remove(KEY_STARTED_AT).apply()
        _state.update { it.copy(active = false, url = null, problem = null) }
    }

    /** Avvia il server sull'indirizzo attuale della TV, o lo sposta se la rete è cambiata. */
    private fun serve() {
        retry?.cancel(false)
        retry = null
        registerNetworkCallback()
        val address = currentAddress()
        if (address == null) {
            stopServer()
            _state.update { it.copy(url = null, problem = GuestProblem.NO_NETWORK) }
            return
        }
        if (server?.isRunning == true && address == serverAddress) {
            syncDlna()
            return
        }
        stopServer()
        val secret = prefs.getString(KEY_SECRET, null) ?: newSecret().also {
            prefs.edit().putString(KEY_SECRET, it).apply()
        }
        val candidate = GuestUploadServer(
            key = secret,
            page = ::page,
            onPhoto = ::receive,
            // Il sistema ha chiuso il server (ad esempio togliendo l'indirizzo): si riavvia a breve.
            onFailure = { scheduleRetry(FAILURE_RETRY_SECONDS) },
            game = phoneGame,
            hub = hub,
            hostRemote = host,
            hostPhoto = store::read,
            play = play,
            extras = pages,
        )
        try {
            val port = candidate.start(address, PREFERRED_PORT)
            server = candidate
            serverAddress = address
            val url = "http://${GuestUploadServer.hostHeader(address, port)}/?k=$secret"
            _state.update { it.copy(url = url, problem = null) }
            syncDlna()
            beacon.advertise(deviceName(), port)
        } catch (failure: IOException) {
            Log.w(TAG, "Server per le foto degli invitati non avviato", failure)
            _state.update { it.copy(url = null, problem = GuestProblem.SERVER_FAILED) }
            scheduleRetry(START_RETRY_SECONDS)
        }
    }

    /** Nuovo tentativo dopo qualche secondo, se nel frattempo la festa è ancora in primo piano. */
    private fun scheduleRetry(seconds: Long) {
        runCatching {
            worker.schedule(Runnable { if (serving()) serve() }, seconds, TimeUnit.SECONDS)
        }.onSuccess { future ->
            worker.execute {
                retry?.cancel(false)
                retry = future
            }
        }
    }

    private fun stopServing() {
        retry?.cancel(false)
        retry = null
        stopServer()
        networkCallback?.let { callback -> runCatching { connectivity?.unregisterNetworkCallback(callback) } }
        networkCallback = null
        networkGeneration++
        networks.clear()
        _state.update { it.copy(url = null, problem = null) }
    }

    private fun stopServer() {
        beacon.stop()
        server?.stop()
        server = null
        serverAddress = null
        syncDlna()
    }

    /** Lo schermo DLNA segue il server: stesso indirizzo, acceso solo se richiesto. */
    private fun syncDlna() {
        val address = serverAddress as? Inet4Address
        val wanted = dlnaEnabled && server?.isRunning == true && address != null
        if (!wanted || address != dlnaAddress) {
            dlna?.stop()
            dlna = null
            dlnaAddress = null
        }
        if (wanted && dlna == null) {
            val renderer = dlnaFactory?.invoke() ?: return
            if (renderer.start(address!!)) {
                dlna = renderer
                dlnaAddress = address
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = connectivity ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            // Basta la rete di casa: la festa funziona anche se il router non ha internet.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val generation = ++networkGeneration
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val address = ipv4(linkProperties)
                worker.execute {
                    // Notifica arrivata dopo che la festa è andata in background: non vale più.
                    if (generation != networkGeneration) return@execute
                    networks[network] = address
                    if (serving()) serve()
                }
            }

            override fun onLost(network: Network) {
                worker.execute {
                    if (generation != networkGeneration) return@execute
                    networks.remove(network)
                    if (serving()) serve()
                }
            }
        }
        if (runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess) networkCallback = callback
    }

    /** Indirizzo IPv4 della TV sulla rete di casa (Wi‑Fi o cavo), o su quella creata dalla TV. */
    private fun currentAddress(): Inet4Address? {
        hotspotAddress?.let { return it }
        networks.values.firstNotNullOfOrNull { it }?.let { return it }
        // Prima delle notifiche della rete si guarda la rete attiva, se è Wi‑Fi o cavo.
        val manager = connectivity ?: return null
        return runCatching {
            val network = manager.activeNetwork ?: return@runCatching null
            val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching null
            val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local) null else manager.getLinkProperties(network)?.let(::ipv4)
        }.getOrNull()
    }

    private fun ipv4(properties: LinkProperties): Inet4Address? =
        properties.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }

    /** Chiamato dai thread del server per ogni foto ricevuta. */
    private fun receive(bytes: ByteArray, sender: String?, player: String?): GuestUploadServer.UploadResult {
        if (!isDisplayable(bytes)) return GuestUploadServer.UploadResult.UNSUPPORTED
        // Limite di foto a testa (una a testa, rullino) e tre minuti senza telefoni.
        when (hub?.takePhoto(player)) {
            PartyHub.PhotoTake.QUIET -> return GuestUploadServer.UploadResult.QUIET
            PartyHub.PhotoTake.LIMIT, PartyHub.PhotoTake.NO_PLAYER -> return GuestUploadServer.UploadResult.LIMIT
            else -> Unit
        }
        val result = when (val added = store.add(bytes, sender, approved = !_state.value.approval)) {
            is GuestPhotoStore.AddResult.Added -> {
                play?.photoArrived(player, added.photo.id)
                if (added.photo.approved) GuestUploadServer.UploadResult.SHOWN else GuestUploadServer.UploadResult.PENDING
            }
            GuestPhotoStore.AddResult.Full -> GuestUploadServer.UploadResult.FULL
            GuestPhotoStore.AddResult.TooManyPending -> GuestUploadServer.UploadResult.TOO_MANY_PENDING
        }
        if (result == GuestUploadServer.UploadResult.FULL || result == GuestUploadServer.UploadResult.TOO_MANY_PENDING) hub?.returnPhoto(player)
        else onPhotoSaved?.invoke(bytes, sender)
        return result
    }

    /** Legge solo le dimensioni: un file danneggiato o enorme non arriva mai allo slideshow. */
    private fun isDisplayable(bytes: ByteArray): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        return width in 1..MAX_IMAGE_SIDE && height in 1..MAX_IMAGE_SIDE && width.toLong() * height <= MAX_IMAGE_PIXELS
    }

    /** Pagina per i telefoni con la misura dello schermo e le impostazioni attuali. */
    private fun page(): String {
        val metrics = context.resources.displayMetrics
        val longSide = max(metrics.widthPixels, metrics.heightPixels).coerceIn(1280, 3840)
        val shortSide = min(metrics.widthPixels, metrics.heightPixels).coerceIn(720, 2160)
        val fit = if (SettingsRepository(context).load().fitMode == FitMode.CROP) "cover" else "contain"
        // Un 10% in più lascia spazio allo zoom lento dello slideshow senza perdere nitidezza.
        val config = """{"maxWidth":${(longSide * ZOOM_MARGIN).roundToInt()},""" +
            """"maxHeight":${(shortSide * ZOOM_MARGIN).roundToInt()},"fit":"$fit","quality":$JPEG_QUALITY,""" +
            """"maxBytes":${GuestUploadServer.MAX_UPLOAD_BYTES},"maxPixels":$MAX_IMAGE_PIXELS,"maxSide":$MAX_IMAGE_SIDE,""" +
            """"approval":${_state.value.approval}}"""
        return template.replace("__FOTOFESTA_CONFIG__", config)
    }

    private fun loadWifi(): WifiNetwork? {
        val ssid = prefs.getString(KEY_WIFI_SSID, null) ?: return null
        val password = runCatching { secrets.get(KEY_WIFI_PASSWORD) }.getOrNull()
        // Senza password leggibile (chiave del Keystore persa) non si mostra un QR sbagliato.
        if (password == null && prefs.contains(KEY_WIFI_PASSWORD)) return null
        return WifiNetwork(ssid, password.orEmpty())
    }

    /** Nome della TV per gli schermi aggiuntivi: quello scelto nelle impostazioni di Android, o il modello. */
    private fun deviceName(): String =
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL.orEmpty().ifBlank { "FotoFesta TV" }

    private fun newSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val TAG = "GuestParty"
        const val PREFERRED_PORT = 8765
        /** Dopo 12 ore senza foto la festa si considera finita. */
        const val IDLE_LIMIT_MS = 12 * 60 * 60 * 1000L
        const val MAX_IMAGE_SIDE = 8_192
        /** Il doppio di uno schermo 4K: la pagina non manda mai di più. */
        const val MAX_IMAGE_PIXELS = 2L * 3840 * 2160
        const val FAILURE_RETRY_SECONDS = 2L
        const val START_RETRY_SECONDS = 5L
        const val ZOOM_MARGIN = 1.1
        const val JPEG_QUALITY = 0.88
        const val KEY_ACTIVE = "active"
        const val KEY_SECRET = "secret"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_APPROVAL = "approval"
        const val KEY_QR_IN_SLIDESHOW = "qr_in_slideshow"
        const val KEY_WIFI_SSID = "wifi_ssid"
        const val KEY_WIFI_PASSWORD = "wifi_password"
        const val HUB_FILE = "party_activities.json"
        const val HUB_SAVE_DELAY_SECONDS = 2L
        /** La parete mostra le ultime 40 foto e 6 dediche. */
        const val WALL_PHOTOS = 40
        const val WALL_MESSAGES = 6
    }
}
