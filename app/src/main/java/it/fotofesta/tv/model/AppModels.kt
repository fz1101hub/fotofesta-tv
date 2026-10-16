package it.fotofesta.tv.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import it.fotofesta.tv.R

enum class Destination { HOME, SETTINGS, SLIDESHOW, GAMES, MEMORY, MYSTERY, PUZZLE, WHO_TOOK_IT, GOOGLE_CONNECT, GUESTS, GUEST_REVIEW, AWARDS, GUEST_WIFI, FILE_BROWSER }

enum class TransitionType(@StringRes val labelRes: Int) {
    FADE(R.string.transition_fade),
    SLIDE(R.string.transition_slide),
    ZOOM(R.string.transition_zoom),
}

/**
 * Come una foto riempie lo schermo. Con [BLUR] la foto è intera e le bande ai lati (foto verticali o
 * non 16:9) mostrano la stessa foto ingrandita e sfocata.
 */
enum class FitMode(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    BLUR(R.string.fit_blur, R.string.fit_blur_body),
    CONTAIN(R.string.fit_contain, R.string.fit_contain_body),
    CROP(R.string.fit_crop, R.string.fit_crop_body),
}

/** Disposizione dello slideshow: una foto alla volta, o la foto nuova in grande con accanto le precedenti. */
enum class SlideshowLayout(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    SINGLE(R.string.layout_single, R.string.layout_single_body),
    MOSAIC(R.string.layout_mosaic, R.string.layout_mosaic_body),
}

/** Sezioni della schermata Impostazioni. */
enum class SettingsCategory { SLIDESHOW, MUSIC, PARTY, SCREENSAVER, PRIVACY }

/**
 * Raccolta scelta, come la mostra la home: sorgente, nome (cartella, Google Foto, foto demo) e numero
 * di foto. Con le foto demo e la festa in corso la raccolta sono le foto degli invitati.
 */
data class CollectionInfo(
    val source: SourceType = SourceType.DEMO,
    val title: String = "",
    val count: Int = 0,
)

/** Errore da mostrare all'utente con un testo localizzato. */
class UserFacingException(@StringRes val messageRes: Int) : Exception()

enum class SourceType { DEMO, USB, NETWORK, GOOGLE_PHOTOS, GUESTS }

sealed interface PhotoRef {
    data class Resource(@DrawableRes val drawable: Int) : PhotoRef
    data class LocalUri(val value: String) : PhotoRef
    data class LocalFile(val path: String) : PhotoRef

    /** Foto di Google Foto caricata al momento da Google: sulla TV non ne resta una copia. */
    data class GooglePhoto(val mediaId: String) : PhotoRef

    /** Foto mandata da un invitato, tenuta da GuestPhotoStore solo per la durata della festa. */
    data class GuestPhoto(val id: String) : PhotoRef

    /** Foto di un server multimediale della rete di casa, caricata al momento (NetworkPhotoFetcher). */
    data class NetworkPhoto(val url: String) : PhotoRef
}

data class PhotoItem(
    val id: String,
    val title: String,
    val ref: PhotoRef,
    val source: SourceType,
    /** Nome di chi ha mandato la foto, per le foto degli invitati. */
    val sender: String? = null,
    /** Quando la foto è arrivata alla TV (epoch ms), per le foto degli invitati. */
    val receivedAtMillis: Long? = null,
)

data class SlideshowSettings(
    val intervalSeconds: Int = 8,
    val transition: TransitionType = TransitionType.FADE,
    val fitMode: FitMode = FitMode.BLUR,
    val layout: SlideshowLayout = SlideshowLayout.SINGLE,
    val shuffle: Boolean = false,
    val reducedMotion: Boolean = false,
    val showClock: Boolean = true,
    val musicVolume: Float = 0.35f,
    /** Zoom e spostamento lenti durante la permanenza di ogni foto (disattivati da [reducedMotion]). */
    val kenBurns: Boolean = false,
    /** Minuti di riproduzione dopo i quali lo slideshow va in pausa; 0 = disattivato. */
    val sleepTimerMinutes: Int = 0,
    /** Conto alla rovescia nello slideshow verso [countdownMinutes] (minuti dalla mezzanotte, ora locale). */
    val countdown: Boolean = false,
    val countdownMinutes: Int = 0,
    val countdownEvent: CountdownEvent = CountdownEvent.MIDNIGHT,
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

/** Cosa si sta scegliendo nel browser di cartelle: una cartella di foto o un brano. */
enum class BrowserKind { PHOTOS, MUSIC }

/** Disco o memoria leggibile dalla TV. */
data class BrowserRoot(val path: String, val label: String, val removable: Boolean)

/** Cartella o file mostrato nel browser. */
data class BrowserEntry(val name: String, val path: String)

/** Server multimediale (DLNA) della rete di casa; [path] apre la sua cartella principale. */
data class BrowserServer(val name: String, val path: String)

/**
 * Browser di cartelle interno, usato al posto del selettore di documenti di Android, che molte TV
 * non hanno. Con [directory] null mostra l'elenco dei dischi e, per le foto, dei server in rete.
 */
data class BrowserState(
    val kind: BrowserKind = BrowserKind.PHOTOS,
    val roots: List<BrowserRoot> = emptyList(),
    val root: BrowserRoot? = null,
    val directory: String? = null,
    val folders: List<BrowserEntry> = emptyList(),
    val files: List<BrowserEntry> = emptyList(),
    /** Foto direttamente nella cartella aperta (senza sottocartelle). */
    val photosHere: Int = 0,
    val loading: Boolean = false,
    /** Il selettore di documenti di Android è disponibile, come alternativa. */
    val systemPickerAvailable: Boolean = false,
    /** Server multimediali trovati nella rete di casa (solo per le foto). */
    val servers: List<BrowserServer> = emptyList(),
    val searchingNetwork: Boolean = false,
    /** Su un server di rete: le cartelle aperte, dalla principale a quella mostrata. */
    val trail: List<BrowserEntry> = emptyList(),
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
    val collection: CollectionInfo = CollectionInfo(),
    /** Sezione aperta nelle impostazioni: resta tale tornando dal browser della musica o dalla Wi‑Fi. */
    val settingsCategory: SettingsCategory = SettingsCategory.SLIDESHOW,
    val settings: SlideshowSettings = SlideshowSettings(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val musicUri: String? = null,
    val musicName: String? = null,
    val google: GoogleConnectState = GoogleConnectState.Idle,
    val busy: Boolean = false,
    val message: String? = null,
    val guests: GuestsUiState = GuestsUiState(),
    val browser: BrowserState = BrowserState(),
    /**
     * Cresce a ogni cambio di foto voluto (successiva, precedente, nuova raccolta). Il cambio
     * automatico si basa su questo e non sull'indice, che si sposta quando arrivano o spariscono
     * foto degli invitati anche se la foto mostrata resta la stessa.
     */
    val slideTick: Int = 0,
    /** Istante (epoch ms) in cui scatterà la pausa automatica, se attiva. */
    val sleepAtMillis: Long? = null,
)
