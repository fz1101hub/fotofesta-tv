package it.fotofesta.tv.tv

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Window
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import it.fotofesta.tv.MainActivity
import it.fotofesta.tv.R
import it.fotofesta.tv.network.LanHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/** Piccole funzioni proprie delle TV Android: memoria, bassa latenza, avvio automatico. */
object TvFeatures {
    /**
     * Fire TV di Amazon (Fire OS): niente salvaschermi di terze parti né canali nella schermata Home,
     * aggiornamenti gestiti dall'Amazon Appstore, comandi vocali di Alexa sulla sessione multimediale.
     */
    fun isFireTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature(FIRE_TV_FEATURE) || Build.MODEL.orEmpty().startsWith("AFT")

    /** TV con poca memoria (le chiavette più economiche): si consiglia la modalità leggera. */
    fun isLowRam(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.isLowRamDevice || manager.memoryClass <= LOW_MEMORY_CLASS_MB
    }

    /**
     * Modalità gioco della TV (ALLM, Android 11+): durante i giochi la TV toglie l'elaborazione
     * dell'immagine, così risposte e disegni arrivano subito sullo schermo.
     */
    fun setLowLatency(window: Window, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setPreferMinimalPostProcessing(enabled)
    }

    /** La TV è collegata alla rete con il cavo. */
    fun isEthernet(context: Context): Boolean {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    data class WifiSignal(val rssi: Int, val frequencyMhz: Int)

    /** Segnale e banda della Wi‑Fi a cui è collegata la TV; null con il cavo o senza Wi‑Fi. */
    @Suppress("DEPRECATION")
    fun wifiSignal(context: Context): WifiSignal? {
        if (isEthernet(context)) return null
        val manager = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return null
        val info = runCatching { manager.connectionInfo }.getOrNull() ?: return null
        if (info.networkId == -1 && info.rssi <= -127) return null
        return WifiSignal(info.rssi, info.frequency)
    }

    private const val LOW_MEMORY_CLASS_MB = 128
    private const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"
}

/**
 * Avvio automatico (locali, vetrine, feste lunghe): all'accensione della TV l'app si apre da sola e
 * riparte lo slideshow. Alcune TV non lasciano aprire app all'accensione: lì non succede nulla.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_START, false)) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_AUTO_START, true),
            )
        }.onFailure { Log.w("BootReceiver", "Avvio automatico non permesso", it) }
    }

    companion object {
        /** Stesse preferenze delle impostazioni dell'app (SettingsRepository). */
        const val PREFS = "slideshow_settings"
        const val KEY_AUTO_START = "auto_start"
    }
}

/**
 * Rete Wi‑Fi della TV (hotspot locale, Android 8+): dove non c'è una rete di casa la TV ne crea una
 * sua, senza internet, e i telefoni la raggiungono con il QR della Wi‑Fi. Molte TV non hanno
 * l'hotspot: allora [start] risponde con un errore.
 */
class TvHotspot(private val context: Context) {
    /** Rete creata: nome, password e indirizzo della TV su quella rete. */
    data class Network(val ssid: String, val password: String, val address: Inet4Address)

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    val active: Boolean get() = reservation != null

    @Suppress("DEPRECATION", "MissingPermission")
    fun start(started: (Network) -> Unit, failed: () -> Unit) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return failed()
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                reservation = value
                val (ssid, password) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val config = value.softApConfiguration
                    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) config.wifiSsid?.toString() else config.ssid
                    name.orEmpty().trim('"') to config.passphrase.orEmpty()
                } else {
                    val config = value.wifiConfiguration
                    config?.SSID.orEmpty().trim('"') to config?.preSharedKey.orEmpty().trim('"')
                }
                val address = hotspotAddress()
                if (address == null || ssid.isEmpty()) {
                    stop()
                    failed()
                } else {
                    started(Network(ssid, password, address))
                }
            }

            override fun onFailed(reason: Int) {
                reservation = null
                failed()
            }

            override fun onStopped() {
                reservation = null
            }
        }
        runCatching { wifi.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper())) }.onFailure {
            Log.w(TAG, "Hotspot non avviato", it)
            failed()
        }
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
    }

    /** Indirizzo della TV sulla rete dell'hotspot (interfacce "ap", "swlan", "wlan1"...). */
    private fun hotspotAddress(): Inet4Address? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && HOTSPOT_NAMES.any { prefix -> it.name.startsWith(prefix) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
    }.getOrNull()

    companion object {
        private const val TAG = "TvHotspot"
        private val HOTSPOT_NAMES = listOf("ap", "swlan", "wlan1", "softap", "wigig")
    }
}

/**
 * Canale di FotoFesta nella schermata Home della TV (Android 8+): scorciatoie per avviare lo
 * slideshow, la festa, i giochi e gli spettacoli. Le immagini sono quelle demo dell'app: le foto
 * degli invitati non lasciano mai l'app.
 */
class HomeChannel(private val context: Context) {
    private val helper = PreviewChannelHelper(context)
    private val prefs = context.getSharedPreferences("home_channel", Context.MODE_PRIVATE)

    // I metodi dei builder di tvprovider 1.0.0 sono API pubbliche, ma lint li vede come riservati
    // perché sono dichiarati nelle classi base generiche della libreria.
    @SuppressLint("RestrictedApi")
    fun publish() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val channelId = prefs.getLong(KEY_CHANNEL, -1L).takeIf { id -> id >= 0 && helper.getPreviewChannel(id) != null }
                ?: helper.publishChannel(
                    PreviewChannel.Builder()
                        .setDisplayName(context.getString(R.string.app_name))
                        .setDescription(context.getString(R.string.channel_description))
                        .setAppLinkIntentUri(link("home"))
                        .apply { BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)?.let { logo -> setLogo(logo) } }
                        .build(),
                ).also { prefs.edit().putLong(KEY_CHANNEL, it).apply() }
            TvContractCompat.requestChannelBrowsable(context, channelId)
            // Programmi rifatti da capo: titoli nella lingua attuale della TV.
            prefs.getString(KEY_PROGRAMS, null).orEmpty().split(',').mapNotNull(String::toLongOrNull).forEach { id ->
                runCatching { helper.deletePreviewProgram(id) }
            }
            val published = PROGRAMS.mapIndexed { index, (route, title, image) ->
                helper.publishPreviewProgram(
                    PreviewProgram.Builder()
                        .setChannelId(channelId)
                        .setType(TvContractCompat.PreviewPrograms.TYPE_EVENT)
                        .setTitle(context.getString(title))
                        .setPosterArtUri(Uri.parse("android.resource://${context.packageName}/drawable/$image"))
                        .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                        .setIntentUri(link(route))
                        .setWeight(PROGRAMS.size - index)
                        .build(),
                )
            }
            prefs.edit().putString(KEY_PROGRAMS, published.joinToString(",")).apply()
        }.onFailure { Log.w(TAG, "Canale della schermata Home non pubblicato", it) }
    }

    fun remove() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val id = prefs.getLong(KEY_CHANNEL, -1L)
        if (id >= 0) runCatching { helper.deletePreviewChannel(id) }
        prefs.edit().remove(KEY_CHANNEL).remove(KEY_PROGRAMS).apply()
    }

    private fun link(route: String): Uri = Uri.parse("fotofesta://open/$route")

    companion object {
        private const val TAG = "HomeChannel"
        private const val KEY_CHANNEL = "channel_id"
        private const val KEY_PROGRAMS = "program_ids"
        private val PROGRAMS = listOf(
            Triple("slideshow", R.string.channel_slideshow, "demo_coast"),
            Triple("party", R.string.channel_party, "demo_birthday"),
            Triple("games", R.string.channel_games, "demo_picnic"),
            Triple("shows", R.string.channel_shows, "demo_mountains"),
        )
    }
}

/**
 * Telecamera e microfono da un vecchio telefono con un'app "IP Webcam" (flusso MJPEG su /video e
 * audio WAV su /audio.wav). Il telefono è nella rete di casa e parla solo HTTP in chiaro: si usa
 * [LanHttp], che non si collega mai fuori dalla rete locale.
 */
class IpCamera(private val http: LanHttp) {
    /** Legge il video finché la coroutine è attiva; lancia un'eccezione se il telefono non risponde. */
    suspend fun stream(base: String, onFrame: (ByteArray) -> Unit) {
        val parser = MjpegParser(onFrame = onFrame)
        read(url(base, "video")) { bytes, length -> parser.feed(bytes, length) }
    }

    /** Applausometro: volume del microfono del telefono, da 0 a 1. */
    suspend fun listen(base: String, onLevel: (Float) -> Unit) {
        val meter = WavMeter(onLevel = onLevel)
        read(url(base, "audio.wav")) { bytes, length -> meter.feed(bytes, length) }
    }

    private suspend fun read(url: String, sink: (ByteArray, Int) -> Unit) = withContext(Dispatchers.IO) {
        val response = http.get(url)
        // Annullando la coroutine si chiude la connessione: la lettura in attesa si sblocca subito.
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                runCatching { response.close() }
            }
        }
        try {
            response.use {
                if (!it.isSuccessful) throw IOException("HTTP ${it.status}")
                val buffer = ByteArray(32 * 1024)
                while (isActive) {
                    val n = it.body.read(buffer)
                    if (n < 0) break
                    sink(buffer, n)
                }
            }
        } catch (failure: IOException) {
            ensureActive()
            throw failure
        } finally {
            closer.cancel()
        }
    }

    companion object {
        /** "192.168.1.20:8080" diventa "http://192.168.1.20:8080/video". */
        fun url(base: String, path: String): String {
            val trimmed = base.trim().trimEnd('/')
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
            val host = withScheme.substringAfter("://").substringBefore('/')
            val root = withScheme.substringBefore("://") + "://" + (if (':' in host) host else "$host:8080")
            return "$root/$path"
        }
    }
}
