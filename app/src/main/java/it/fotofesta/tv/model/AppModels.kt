package it.fotofesta.tv.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import it.fotofesta.tv.R

enum class Destination { HOME, SETTINGS, SLIDESHOW, GAMES, MEMORY, MYSTERY, GOOGLE_CONNECT, GUESTS, GUEST_REVIEW, GUEST_WIFI }

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

enum class SourceType { DEMO, USB, GOOGLE_PHOTOS, GUESTS }

sealed interface PhotoRef {
    data class Resource(@DrawableRes val drawable: Int) : PhotoRef
    data class LocalUri(val value: String) : PhotoRef
    data class LocalFile(val path: String) : PhotoRef

    /** Foto di Google Foto caricata al momento da Google: sulla TV non ne resta una copia. */
    data class GooglePhoto(val mediaId: String) : PhotoRef

    /** Foto mandata da un invitato, tenuta da GuestPhotoStore solo per la durata della festa. */
    data class GuestPhoto(val id: String) : PhotoRef
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

sealed interface GoogleConnectState {
    data object Idle : GoogleConnectState
    data class WaitingForAuthorization(
        val verificationUrl: String,
        val userCode: String,
        val expiresInSeconds: Int,
    ) : GoogleConnectState
    data class WaitingForPhotos(val pickerUri: String) : GoogleConnectState
    data class Error(val message: String) : GoogleConnectState

    /** [expiresAtMillis]: fino a quando Google rende disponibili le foto scelte, se noto. */
    data class Complete(val count: Int, val expiresAtMillis: Long? = null) : GoogleConnectState
}

/** Perché la TV non riesce a ricevere le foto degli invitati. */
enum class GuestProblem { NO_NETWORK, SERVER_FAILED }

/** Stato della ricezione delle foto degli invitati, come lo mostra l'interfaccia. */
data class GuestsUiState(
    /** La festa è in corso: la TV riceve foto quando l'app è in primo piano. */
    val active: Boolean = false,
    /** Indirizzo della pagina per i telefoni, codificato nel QR; null finché la TV non è in rete. */
    val url: String? = null,
    val problem: GuestProblem? = null,
    /** Le foto compaiono solo dopo l'approvazione dalla TV. */
    val approval: Boolean = false,
    /** QR piccolo nell'angolo dello slideshow, così gli invitati possono inquadrarlo in ogni momento. */
    val qrInSlideshow: Boolean = true,
    /** Nome e password della rete Wi‑Fi del QR per entrare in rete, se impostata. */
    val wifiName: String? = null,
    val wifiPassword: String? = null,
    val wifiQr: String? = null,
    /** Foto approvate in mostra. */
    val shown: Int = 0,
    /** Foto in attesa di approvazione, dalla più vecchia. */
    val pending: List<PhotoItem> = emptyList(),
)

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
    val guests: GuestsUiState = GuestsUiState(),
    /**
     * Cresce a ogni cambio di foto voluto (successiva, precedente, nuova raccolta). Il cambio
     * automatico si basa su questo e non sull'indice, che si sposta quando arrivano o spariscono
     * foto degli invitati anche se la foto mostrata resta la stessa.
     */
    val slideTick: Int = 0,
    /** Istante (epoch ms) in cui scatterà la pausa automatica, se attiva. */
    val sleepAtMillis: Long? = null,
)
