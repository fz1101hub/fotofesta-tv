package it.fotofesta.tv.data

import android.content.Context
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.TransitionType

/**
 * Preferenze dell'app, divise in due file:
 * - `slideshow_settings`: impostazioni dello slideshow, incluse nel backup del sistema;
 * - `device_sources`: cartella USB, brano e ultima sorgente. Restano escluse dal backup perché i permessi
 *   sugli URI non passano a un altro dispositivo e perché indicano contenuti presenti solo su questa TV.
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("slideshow_settings", Context.MODE_PRIVATE)
    private val sources = context.getSharedPreferences("device_sources", Context.MODE_PRIVATE)

    data class SavedMusic(val uri: String, val name: String)

    init {
        migrateSourcesOutOfBackup()
    }

    fun load(): SlideshowSettings = SlideshowSettings(
        intervalSeconds = prefs.getInt("interval", 8),
        transition = enumValueOrDefault(prefs.getString("transition", null), TransitionType.FADE),
        fitMode = enumValueOrDefault(prefs.getString("fit", null), FitMode.CONTAIN),
        shuffle = prefs.getBoolean("shuffle", false),
        reducedMotion = prefs.getBoolean("reduced_motion", false),
        showClock = prefs.getBoolean("clock", true),
        musicVolume = prefs.getFloat("volume", 0.35f),
        kenBurns = prefs.getBoolean("ken_burns", false),
        sleepTimerMinutes = prefs.getInt("sleep_timer", 0),
    )

    fun save(value: SlideshowSettings) {
        prefs.edit()
            .putInt("interval", value.intervalSeconds)
            .putString("transition", value.transition.name)
            .putString("fit", value.fitMode.name)
            .putBoolean("shuffle", value.shuffle)
            .putBoolean("reduced_motion", value.reducedMotion)
            .putBoolean("clock", value.showClock)
            .putFloat("volume", value.musicVolume)
            .putBoolean("ken_burns", value.kenBurns)
            .putInt("sleep_timer", value.sleepTimerMinutes)
            .apply()
    }

    fun loadMusic(): SavedMusic? {
        val uri = sources.getString("music_uri", null) ?: return null
        return SavedMusic(uri, sources.getString("music_name", null).orEmpty())
    }

    fun saveMusic(uri: String, name: String) {
        sources.edit().putString("music_uri", uri).putString("music_name", name).apply()
    }

    fun clearMusic() {
        sources.edit().remove("music_uri").remove("music_name").apply()
    }

    fun saveUsbTree(uri: String) {
        sources.edit().putString("usb_tree_uri", uri).putString("last_source", "USB").apply()
    }

    fun loadUsbTree(): String? = sources.getString("usb_tree_uri", null)

    fun saveLastSource(source: String) {
        sources.edit().putString("last_source", source).apply()
    }

    fun loadLastSource(): String = sources.getString("last_source", "DEMO") ?: "DEMO"

    /** Le versioni fino alla 1.2.0 salvavano le sorgenti nel file incluso nel backup: le sposta una volta. */
    private fun migrateSourcesOutOfBackup() {
        val keys = listOf("music_uri", "music_name", "usb_tree_uri", "last_source").filter(prefs::contains)
        if (keys.isEmpty()) return
        val moved = sources.edit()
        val removed = prefs.edit()
        keys.forEach { key ->
            if (!sources.contains(key)) prefs.getString(key, null)?.let { moved.putString(key, it) }
            removed.remove(key)
        }
        // Prima si scrivono le sorgenti e solo dopo si cancellano dal vecchio file.
        moved.commit()
        removed.apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
