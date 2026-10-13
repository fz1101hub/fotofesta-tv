package it.fotofesta.tv.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import it.fotofesta.tv.R

enum class Destination { HOME, SETTINGS, SLIDESHOW, GAMES, MEMORY, MYSTERY, GOOGLE_CONNECT }

enum class TransitionType(@StringRes val labelRes: Int) {
    FADE(R.string.transition_fade),
    SLIDE(R.string.transition_slide),
    ZOOM(R.string.transition_zoom),
}

enum class FitMode(@StringRes val labelRes: Int) {
    CONTAIN(R.string.fit_contain),
    CROP(R.string.fit_crop),
}

/** Errore da mostrare all'utente con un testo localizzato. */
class UserFacingException(@StringRes val messageRes: Int) : Exception()

enum class SourceType { DEMO, USB, GOOGLE_PHOTOS, CAST }

sealed interface PhotoRef {
    data class Resource(@DrawableRes val drawable: Int) : PhotoRef
    data class LocalUri(val value: String) : PhotoRef
    data class LocalFile(val path: String) : PhotoRef
    data class Remote(val url: String) : PhotoRef
}

data class PhotoItem(
    val id: String,
    val title: String,
    val ref: PhotoRef,
    val source: SourceType,
)

data class SlideshowSettings(
    val intervalSeconds: Int = 8,
    val transition: TransitionType = TransitionType.FADE,
    val fitMode: FitMode = FitMode.CONTAIN,
    val shuffle: Boolean = false,
    val reducedMotion: Boolean = false,
    val showClock: Boolean = true,
    val musicVolume: Float = 0.35f,
    /** Zoom e spostamento lenti durante la permanenza di ogni foto (disattivati da [reducedMotion]). */
    val kenBurns: Boolean = false,
    /** Minuti di riproduzione dopo i quali lo slideshow va in pausa; 0 = disattivato. */
    val sleepTimerMinutes: Int = 0,
)

/** Aggiornamento parziale delle impostazioni: i campi `null` lasciano invariato il valore corrente. */
data class SettingsPatch(
    val intervalSeconds: Int? = null,
    val transition: TransitionType? = null,
    val fitMode: FitMode? = null,
    val shuffle: Boolean? = null,
    val reducedMotion: Boolean? = null,
    val showClock: Boolean? = null,
    val musicVolume: Float? = null,
    val kenBurns: Boolean? = null,
    val sleepTimerMinutes: Int? = null,
) {
    fun applyTo(base: SlideshowSettings) = SlideshowSettings(
        intervalSeconds = intervalSeconds ?: base.intervalSeconds,
        transition = transition ?: base.transition,
        fitMode = fitMode ?: base.fitMode,
        shuffle = shuffle ?: base.shuffle,
        reducedMotion = reducedMotion ?: base.reducedMotion,
        showClock = showClock ?: base.showClock,
        musicVolume = musicVolume ?: base.musicVolume,
        kenBurns = kenBurns ?: base.kenBurns,
        sleepTimerMinutes = sleepTimerMinutes ?: base.sleepTimerMinutes,
    )
}

sealed interface GoogleConnectState {
    data object Idle : GoogleConnectState
    data class WaitingForAuthorization(
        val verificationUrl: String,
        val userCode: String,
        val expiresInSeconds: Int,
    ) : GoogleConnectState
    data class WaitingForPhotos(val pickerUri: String) : GoogleConnectState
    data class Downloading(val completed: Int, val total: Int) : GoogleConnectState
    data class Error(val message: String) : GoogleConnectState
    data class Complete(val count: Int) : GoogleConnectState
}

data class MemoryCard(
    val key: Int,
    val photo: PhotoItem,
    val revealed: Boolean = false,
    val matched: Boolean = false,
)

data class AppState(
    val destination: Destination = Destination.HOME,
    val photos: List<PhotoItem> = emptyList(),
    val sourceLabel: String = "",
    val settings: SlideshowSettings = SlideshowSettings(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val musicUri: String? = null,
    val musicName: String? = null,
    val google: GoogleConnectState = GoogleConnectState.Idle,
    val busy: Boolean = false,
    val message: String? = null,
    val castSenderConnected: Boolean = false,
    /** Istante (epoch ms) in cui scatterà la pausa automatica, se attiva. */
    val sleepAtMillis: Long? = null,
)
