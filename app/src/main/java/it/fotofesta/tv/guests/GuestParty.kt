package it.fotofesta.tv.guests

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import it.fotofesta.tv.data.KeystoreStrings
import it.fotofesta.tv.data.SettingsRepository
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.GuestProblem
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
) {
    data class State(
        val active: Boolean = false,
        val url: String? = null,
        val problem: GuestProblem? = null,
        val approval: Boolean = false,
        val qrInSlideshow: Boolean = true,
        val wifi: WifiNetwork? = null,
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
    private val template by lazy {
        context.assets.open("guest/index.html").use { it.readBytes().toString(Charsets.UTF_8) }
    }

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
            if (System.currentTimeMillis() - lastActivity > IDLE_LIMIT_MS) endParty()
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
        if (server?.isRunning == true && address == serverAddress) return
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
        )
        try {
            val port = candidate.start(address, PREFERRED_PORT)
            server = candidate
            serverAddress = address
            val url = "http://${GuestUploadServer.hostHeader(address, port)}/?k=$secret"
            _state.update { it.copy(url = url, problem = null) }
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
        server?.stop()
        server = null
        serverAddress = null
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

    /** Indirizzo IPv4 della TV sulla rete di casa (Wi‑Fi o cavo). */
    private fun currentAddress(): Inet4Address? {
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
    private fun receive(bytes: ByteArray, sender: String?): GuestUploadServer.UploadResult {
        if (!isDisplayable(bytes)) return GuestUploadServer.UploadResult.UNSUPPORTED
        return when (val result = store.add(bytes, sender, approved = !_state.value.approval)) {
            is GuestPhotoStore.AddResult.Added ->
                if (result.photo.approved) GuestUploadServer.UploadResult.SHOWN else GuestUploadServer.UploadResult.PENDING
            GuestPhotoStore.AddResult.Full -> GuestUploadServer.UploadResult.FULL
            GuestPhotoStore.AddResult.TooManyPending -> GuestUploadServer.UploadResult.TOO_MANY_PENDING
        }
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
    }
}
