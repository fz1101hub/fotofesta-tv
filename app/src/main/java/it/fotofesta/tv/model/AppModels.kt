package it.fotofesta.tv.model

import androidx.annotation.DrawableRes

enum class Destination { HOME, SOURCES, SETTINGS, SLIDESHOW, GAMES, MEMORY, MYSTERY, GOOGLE_CONNECT }
enum class TransitionType(val label: String) { FADE("Dissolvenza"), SLIDE("Scorrimento"), ZOOM("Zoom dolce") }
enum class FitMode(val label: String) { CONTAIN("Foto intera"), CROP("Riempi schermo") }
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
)

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
    val sourceLabel: String = "Foto dimostrative",
    val settings: SlideshowSettings = SlideshowSettings(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val musicUri: String? = null,
    val musicName: String? = null,
    val google: GoogleConnectState = GoogleConnectState.Idle,
    val busy: Boolean = false,
    val message: String? = null,
    val castSenderConnected: Boolean = false,
)
