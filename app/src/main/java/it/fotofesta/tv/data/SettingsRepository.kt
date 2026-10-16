package it.fotofesta.tv.data

import android.annotation.SuppressLint
import android.content.Context
import it.fotofesta.tv.model.Countdown
import it.fotofesta.tv.model.CountdownEvent
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.SlideshowLayout
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.TransitionType
import it.fotofesta.tv.network.NetworkFolder

/**
 * Preferenze dell'app, divise in due file:
 * - `slideshow_settings`: impostazioni dello slideshow, incluse nel backup del sistema;
 * - `device_sources`: cartella USB, brano e ultima sorgente. Restano escluse dal backup perché i permessi
 *   sugli URI non passano a un altro dispositivo e perché indicano contenuti presenti solo su questa TV.
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("slideshow_settings", Context.MODE_PRIVATE)
    private val sources = context.getSharedPreferences("device_sources", Context.MODE_PRIVATE)

    /** Brano o cartella di brani ([folder]) scelti per la musica di sottofondo. */
    data class SavedMusic(val uri: String, val name: String, val folder: Boolean = false)

    init {
        migrateSourcesOutOfBackup()
    }

    fun load(): SlideshowSettings = SlideshowSettings(
        intervalSeconds = prefs.getInt("interval", 8),
        transition = enumValueOrDefault(prefs.getString("transition", null), TransitionType.FADE),
        // Chiave nuova dalla 1.3.0: "Foto intera" era il valore predefinito e lo sfondo sfocato lo
        // sostituisce; resta solo la scelta esplicita di riempire lo schermo.
        fitMode = prefs.getString("fit_mode", null)?.let { enumValueOrDefault(it, FitMode.BLUR) }
            ?: if (prefs.getString("fit", null) == FitMode.CROP.name) FitMode.CROP else FitMode.BLUR,
        layout = enumValueOrDefault(prefs.getString("layout", null), SlideshowLayout.SINGLE),
        shuffle = prefs.getBoolean("shuffle", false),
        reducedMotion = prefs.getBoolean("reduced_motion", false),
        showClock = prefs.getBoolean("clock", true),
        musicVolume = prefs.getFloat("volume", 0.35f),
        kenBurns = prefs.getBoolean("ken_burns", false),
        sleepTimerMinutes = prefs.getInt("sleep_timer", 0),
        countdown = prefs.getBoolean("countdown", false),
        countdownMinutes = prefs.getInt("countdown_minutes", 0).takeIf { it in Countdown.TIME_OPTIONS } ?: 0,
        countdownEvent = enumValueOrDefault(prefs.getString("countdown_event", null), CountdownEvent.MIDNIGHT),
        videos = prefs.getBoolean("videos", true),
        videoSound = prefs.getBoolean("video_sound", true),
    )

    fun save(value: SlideshowSettings) {
        prefs.edit()
            .putInt("interval", value.intervalSeconds)
            .putString("transition", value.transition.name)
            .putString("fit_mode", value.fitMode.name)
            .putString("layout", value.layout.name)
            .putBoolean("shuffle", value.shuffle)
            .putBoolean("reduced_motion", value.reducedMotion)
            .putBoolean("clock", value.showClock)
            .putFloat("volume", value.musicVolume)
            .putBoolean("ken_burns", value.kenBurns)
            .putInt("sleep_timer", value.sleepTimerMinutes)
            .putBoolean("countdown", value.countdown)
            .putInt("countdown_minutes", value.countdownMinutes)
            .putString("countdown_event", value.countdownEvent.name)
            .putBoolean("videos", value.videos)
            .putBoolean("video_sound", value.videoSound)
            .apply()
    }

    fun loadMusic(): SavedMusic? {
        val uri = sources.getString("music_uri", null) ?: return null
        return SavedMusic(uri, sources.getString("music_name", null).orEmpty(), sources.getBoolean("music_folder", false))
    }

    fun saveMusic(uri: String, name: String, folder: Boolean = false) {
        sources.edit().putString("music_uri", uri).putString("music_name", name).putBoolean("music_folder", folder).apply()
    }

    fun clearMusic() {
        sources.edit().remove("music_uri").remove("music_name").remove("music_folder").apply()
    }

    /** Cartella scelta con il selettore di documenti di Android. */
    fun saveUsbTree(uri: String) {
        sources.edit().putString("usb_tree_uri", uri).remove("usb_folder").putString("last_source", "USB").apply()
    }

    fun loadUsbTree(): String? = sources.getString("usb_tree_uri", null)

    /** Cartella scelta con il browser interno, come percorso sul disco. */
    fun saveUsbFolder(path: String) {
        sources.edit().putString("usb_folder", path).remove("usb_tree_uri").putString("last_source", "USB").apply()
    }

    fun loadUsbFolder(): String? = sources.getString("usb_folder", null)

    /** Cartella di un server multimediale della rete di casa. */
    fun saveNetworkFolder(folder: NetworkFolder) {
        sources.edit()
            .putString("network_udn", folder.udn)
            .putString("network_location", folder.location)
            .putString("network_container", folder.containerId)
            .putString("network_title", folder.title)
            .putString("last_source", "NETWORK")
            .apply()
    }

    /** Album condiviso di iCloud: il codice del link pubblico e il nome, per il browser. */
    fun saveICloudAlbum(token: String, name: String) {
        sources.edit().putString("icloud_token", token).putString("icloud_name", name).putString("last_source", "ICLOUD").apply()
    }

    fun loadICloudToken(): String? = sources.getString("icloud_token", null)

    fun loadICloudName(): String? = sources.getString("icloud_name", null)

    fun loadNetworkFolder(): NetworkFolder? {
        val udn = sources.getString("network_udn", null) ?: return null
        val location = sources.getString("network_location", null) ?: return null
        val container = sources.getString("network_container", null) ?: return null
        return NetworkFolder(udn, location, container, sources.getString("network_title", null).orEmpty())
    }

    fun saveLastSource(source: String) {
        sources.edit().putString("last_source", source).apply()
    }

    fun loadLastSource(): String = sources.getString("last_source", "DEMO") ?: "DEMO"

    /** Le versioni precedenti salvavano le sorgenti nel file incluso nel backup: le sposta una volta. */
    @SuppressLint("ApplySharedPref") // commit sincrono voluto: le chiavi si tolgono dal vecchio file solo dopo
    private fun migrateSourcesOutOfBackup() {
        val keys = listOf("music_uri", "music_name", "usb_tree_uri", "last_source").filter(prefs::contains)
        // usb_folder è nato direttamente in device_sources: non serve spostarlo.
        if (keys.isEmpty()) return
        val moved = sources.edit()
        val removed = prefs.edit()
        keys.forEach { key ->
            if (!sources.contains(key)) prefs.getString(key, null)?.let { moved.putString(key, it) }
            removed.remove(key)
        }
        // Prima si scrivono le sorgenti e solo se la scrittura riesce si cancellano dal vecchio file.
        if (moved.commit()) removed.apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
