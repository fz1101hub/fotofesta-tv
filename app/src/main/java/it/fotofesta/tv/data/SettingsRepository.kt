package it.fotofesta.tv.data

import android.content.Context
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.TransitionType

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("slideshow_settings", Context.MODE_PRIVATE)

    data class SavedMusic(val uri: String, val name: String)

    fun load(): SlideshowSettings = SlideshowSettings(
        intervalSeconds = prefs.getInt("interval", 8),
        transition = enumValueOrDefault(prefs.getString("transition", null), TransitionType.FADE),
        fitMode = enumValueOrDefault(prefs.getString("fit", null), FitMode.CONTAIN),
        shuffle = prefs.getBoolean("shuffle", false),
        reducedMotion = prefs.getBoolean("reduced_motion", false),
        highContrast = prefs.getBoolean("high_contrast", false),
        showClock = prefs.getBoolean("clock", true),
        musicVolume = prefs.getFloat("volume", 0.35f),
    )

    fun save(value: SlideshowSettings) {
        prefs.edit()
            .putInt("interval", value.intervalSeconds)
            .putString("transition", value.transition.name)
            .putString("fit", value.fitMode.name)
            .putBoolean("shuffle", value.shuffle)
            .putBoolean("reduced_motion", value.reducedMotion)
            .putBoolean("high_contrast", value.highContrast)
            .putBoolean("clock", value.showClock)
            .putFloat("volume", value.musicVolume)
            .apply()
    }

    fun loadMusic(): SavedMusic? {
        val uri = prefs.getString("music_uri", null) ?: return null
        return SavedMusic(uri, prefs.getString("music_name", null) ?: "Brano scelto")
    }

    fun saveMusic(uri: String, name: String) {
        prefs.edit().putString("music_uri", uri).putString("music_name", name).apply()
    }

    fun clearMusic() {
        prefs.edit().remove("music_uri").remove("music_name").apply()
    }

    fun saveUsbTree(uri: String) {
        prefs.edit().putString("usb_tree_uri", uri).putString("last_source", "USB").apply()
    }

    fun loadUsbTree(): String? = prefs.getString("usb_tree_uri", null)

    fun saveLastSource(source: String) {
        prefs.edit().putString("last_source", source).apply()
    }

    fun loadLastSource(): String = prefs.getString("last_source", "DEMO") ?: "DEMO"

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
