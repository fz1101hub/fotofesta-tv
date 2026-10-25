package it.fotofesta.tv.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import it.fotofesta.tv.R
import it.fotofesta.tv.guests.PartyHub

enum class Destination { HOME, SETTINGS, SLIDESHOW, GAMES, MEMORY, MYSTERY, PUZZLE, WHO_TOOK_IT, POLL, LOTTERY, GOOGLE_CONNECT, GUESTS, GUEST_REVIEW, AWARDS, GUESTBOOK, ANNOUNCE, GUEST_WIFI, FILE_BROWSER, ICLOUD_LINK, SMB_LOGIN,
    PARTY_GAMES, PLAY, TOURNAMENT, BETS, TOAST, COINS,
    SHOWS, REWIND, BARCODE, PHOTO_MOSAIC, CREDITS, TELETEXT, SKY, STATS,
    BUZZER, CAMERA, DOOR, EXTRA_SCREEN, ANYWHERE, PHONE_WALL, NETWORK_CHECK }

enum class TransitionType(@StringRes val labelRes: Int) {
    FADE(R.string.transition_fade),
    SLIDE(R.string.transition_slide),
    ZOOM(R.string.transition_zoom),
    /** Un attimo di "neve" della TV e il numero del canale, come cambiando canale. */
    ZAPPING(R.string.transition_zapping),
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
    /** Le foto cadono come polaroid su una pila, una sopra l'altra. */
    STACK(R.string.layout_stack, R.string.layout_stack_body),
}

/** Quante foto può mandare ogni invitato: senza limite, una sola, o il rullino usa e getta. */
enum class PhotoLimit(@StringRes val labelRes: Int, val perGuest: Int) {
    NONE(R.string.photo_limit_none, 0),
    ONE(R.string.photo_limit_one, 1),
    DISPOSABLE(R.string.photo_limit_disposable, PartyHub.DISPOSABLE_SHOTS),
}

/** Sezioni della schermata Impostazioni. */
enum class SettingsCategory { SLIDESHOW, MUSIC, PARTY, TV, SCREENSAVER, PRIVACY }

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

enum class SourceType { DEMO, USB, NETWORK, ICLOUD, GOOGLE_PHOTOS, GUESTS }

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

    /** Foto o video di un album condiviso di iCloud, caricato al momento (ICloudPhotoFetcher). */
    data class ICloudPhoto(val guid: String) : PhotoRef

    /** Foto o video di una cartella condivisa (SMB) di un computer o NAS, letto al momento (SmbPhotoFetcher). */
    data class SmbPhoto(val path: String) : PhotoRef
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
    /** È un video: nello slideshow si guarda fino alla fine; giochi e salvaschermo lo saltano. */
    val video: Boolean = false,
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
    /** Slideshow a ritmo: con la musica di sottofondo le foto cambiano a inizio battuta e pulsano sul battito. */
    val beatSync: Boolean = false,
    /** Gli invitati chiedono canzoni dal telefono; chi fa la musica le vede nella regia. */
    val songRequests: Boolean = true,
    /** Gli invitati possono scaricare dal telefono le foto approvate della festa (ZIP). */
    val guestAlbum: Boolean = true,
    /** Stesso indirizzo della festa a ogni serata: cartoncini con il QR e adesivi NFC restano validi. */
    val fixedAddress: Boolean = false,
    /** Video brevi dagli invitati (non con l'approvazione delle foto né con la festa senza tracce). */
    val guestVideos: Boolean = true,
    /** Cornice dell'evento sullo slideshow, con nome e logo scelti dalla regia. */
    val frameStyle: FrameStyle = FrameStyle.NONE,
    /** Uno sponsor ogni tante foto (0 = mai). */
    val sponsorEvery: Int = 0,
    /** Margini dello schermo in percentuale, per le TV e i proiettori che tagliano i bordi. */
    val screenMargin: Int = 0,
    /** Correzione trapezio per un proiettore inclinato: da -4 a 4, 0 = spenta. */
    val keystone: Int = 0,
    /** Pulsante Bluetooth da tavolo (o tasto fotocamera/volume su): foto successiva con i coriandoli. */
    val tableButton: Boolean = false,
    /** Gli annunci della regia si leggono anche ad alta voce. */
    val speakAnnouncements: Boolean = false,
    /** Chiosco: impostazioni, fine festa e uscita dall'app chiedono il PIN. */
    val kiosk: Boolean = false,
    /** Locale fisso: all'apertura dell'app la festa parte da sola. */
    val alwaysParty: Boolean = false,
    /** Locale fisso: ora in cui la festa si azzera ogni notte (-1 = mai). */
    val nightlyReset: Int = -1,
    /** Zoom e spostamento lenti durante la permanenza di ogni foto (disattivati da [reducedMotion]). */
    val kenBurns: Boolean = false,
    /** Minuti di riproduzione dopo i quali lo slideshow va in pausa; 0 = disattivato. */
    val sleepTimerMinutes: Int = 0,
    /** Conto alla rovescia nello slideshow verso [countdownMinutes] (minuti dalla mezzanotte, ora locale). */
    val countdown: Boolean = false,
    val countdownMinutes: Int = 0,
    val countdownEvent: CountdownEvent = CountdownEvent.MIDNIGHT,
    /** Video della raccolta nello slideshow, insieme alle foto. */
    val videos: Boolean = true,
    /** Audio dei video: mentre suona, la musica di sottofondo si ferma. */
    val videoSound: Boolean = true,
    /** Durante la festa i telefoni mandano reazioni alla foto sullo schermo. */
    val phoneReactions: Boolean = true,
    /** Durante la festa i telefoni mandano dediche, mostrate nello slideshow e nel libro degli ospiti. */
    val phoneMessages: Boolean = true,
    /** Chi organizza comanda la festa dal telefono, con il PIN mostrato sulla TV. */
    val phoneHost: Boolean = true,
    /** Limite di foto per invitato; con il rullino le foto si vedono dopo lo sviluppo. */
    val photoLimit: PhotoLimit = PhotoLimit.NONE,
    /** Le foto ingialliscono a ogni passaggio; le reazioni le ringiovaniscono. */
    val agingPhotos: Boolean = false,
    /** Ogni foto degli invitati si mostra una volta sola e poi si cancella. */
    val noTraces: Boolean = false,
    /** Ogni foto va subito anche su disco: se l'app si chiude, la festa riprende. */
    val resumeParty: Boolean = true,
    /** Copia delle foto degli invitati in Immagini/FotoFesta (Android 10 e successivi). */
    val backupCopy: Boolean = false,
    /** Filtro sulle foto dello slideshow. */
    val filter: PhotoFilter = PhotoFilter.NONE,
    /** Coriandoli quando arriva una foto nuova degli invitati o una reazione 🎉. */
    val confetti: Boolean = false,
    /** "TG della festa": titolo in sovrimpressione e notizie che scorrono (dediche, numeri). */
    val newsMode: Boolean = false,
    /** Sigla d'apertura all'avvio dello slideshow. */
    val openingTitles: Boolean = false,
    /** TV montata in verticale (totem): lo slideshow ruota di 90°. */
    val portrait: Boolean = false,
    /** Modello di festa scelto: dà il titolo a sigla, TG, titoli di coda e Televideo. */
    val template: PartyTemplate = PartyTemplate.NONE,
    /** Modalità leggera per le TV con poca memoria: niente sfocature, zoom lenti ed effetti pesanti. */
    val lightMode: Boolean = false,
    /** Proiettore: colori e contrasto più forti, bande nere al posto dello sfondo sfocato. */
    val projectorMode: Boolean = false,
    /** All'accensione della TV l'app si apre e riparte lo slideshow (locali, vetrine). */
    val autoStart: Boolean = false,
    /** Durante la festa la TV compare tra gli schermi DLNA dei telefoni e ne riceve le foto. */
    val dlnaReceiver: Boolean = false,
    /** Scorciatoie di FotoFesta nella schermata Home della TV. */
    val homeChannel: Boolean = true,
) {
    /**
     * Impostazioni come le usa lo schermo: la modalità leggera toglie ciò che pesa sulle TV più
     * economiche, il proiettore toglie lo sfondo sfocato, che sul muro sembra luce sporca.
     */
    fun forScreen(): SlideshowSettings {
        var result = this
        if (lightMode) {
            result = result.copy(
                fitMode = if (fitMode == FitMode.BLUR) FitMode.CONTAIN else fitMode,
                kenBurns = false,
                layout = SlideshowLayout.SINGLE,
                transition = if (transition == TransitionType.SLIDE) transition else TransitionType.FADE,
                confetti = false,
            )
        }
        if (projectorMode && result.fitMode == FitMode.BLUR) result = result.copy(fitMode = FitMode.CONTAIN)
        return result
    }
}

sealed interface GoogleConnectState {
    data object Idle : GoogleConnectState
    /** [pageUrl]: pagina della TV da inquadrare con il telefono; da lì si va da Google. */
    data class WaitingForAuthorization(
        val pageUrl: String,
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
    /** La Wi‑Fi del QR è la rete creata dalla TV (hotspot), senza internet. */
    val hotspot: Boolean = false,
    /** Foto approvate in mostra. */
    val shown: Int = 0,
    /** Foto in attesa di approvazione, dalla più vecchia. */
    val pending: List<PhotoItem> = emptyList(),
    /** Indirizzo da scrivere a mano ("festa.local:8765") e codice della festa. */
    val localUrl: String? = null,
    val joinCode: String? = null,
)

/** Dediche arrivate dai telefoni: quelle in mostra (dalla più vecchia) e quelle da approvare. */
/** Prova della rete della festa: cosa si controlla e come è andata. */
data class NetworkCheck(
    val network: CheckStatus = CheckStatus.WAITING,
    val networkDetail: String? = null,
    val server: CheckStatus = CheckStatus.WAITING,
    val internet: CheckStatus = CheckStatus.WAITING,
    val signal: CheckStatus = CheckStatus.WAITING,
    val signalDetail: String? = null,
    val phone: CheckStatus = CheckStatus.WAITING,
)

enum class CheckStatus { WAITING, OK, WARN, FAIL }

/** Perché si chiede il PIN del chiosco. */
enum class PinPurpose { SET, DISABLE, SETTINGS, END_PARTY, EXIT }

/** Cornice dell'evento: nessuna, fascia in alto con nome e logo, bordo nei colori della festa. */
enum class FrameStyle { NONE, BAND, BORDER }

data class PartyUiState(
    val messages: List<PartyHub.Message> = emptyList(),
    val pendingMessages: List<PartyHub.Message> = emptyList(),
    /** Ultimo annuncio della regia: la TV lo mostra per qualche secondo dopo l'arrivo. */
    val announcement: PartyHub.Announcement? = null,
    /** Invitato che dirige lo slideshow dal telefono, finché dura il suo turno. */
    val director: PartyHub.Director? = null,
    /** PIN della regia dal telefono; null se la regia è spenta. */
    val hostPin: String? = null,
    /** Fine dei tre minuti senza telefoni (epoch ms); 0 se non sono in corso. */
    val quietUntilMillis: Long = 0,
    /** Rullino usa e getta non ancora sviluppato, con gli scatti usati finora. */
    val undeveloped: Boolean = false,
    val shotsTaken: Int = 0,
    /** Prova generale in corso, con telefoni simulati. */
    val rehearsal: Boolean = false,
    /** Nomi di chi è arrivato dal QR dell'ingresso, in ordine. */
    val arrivals: List<String> = emptyList(),
)

/** Giochi con i telefoni: gioco in corso, caccia al QR, luci e monete. */
data class PlayUiState(
    val game: it.fotofesta.tv.guests.PartyPlay.State? = null,
    /** Indirizzo del QR della caccia in corso (la pagina della festa con il codice). */
    val treasureUrl: String? = null,
    val treasureFinders: List<String> = emptyList(),
    val lights: it.fotofesta.tv.guests.PartyPlay.Lights? = null,
    val coins: List<it.fotofesta.tv.guests.PartyPlay.Coins> = emptyList(),
)

/** Cosa si sta scegliendo nel browser di cartelle: una cartella di foto o un brano. */
enum class BrowserKind { PHOTOS, MUSIC }

/** Disco o memoria leggibile dalla TV. */
data class BrowserRoot(val path: String, val label: String, val removable: Boolean)

/** Cartella o file mostrato nel browser. */
data class BrowserEntry(val name: String, val path: String)

/**
 * Server multimediale (DLNA) o computer con cartelle condivise (SMB) della rete di casa; [path] apre
 * la sua cartella principale o l'elenco delle sue condivisioni.
 */
data class BrowserServer(val name: String, val path: String, val detail: String? = null)

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
    /** Computer e NAS con cartelle condivise (SMB) trovati nella rete di casa. */
    val smbHosts: List<BrowserServer> = emptyList(),
    /** Cartella condivisa già scelta, per riaprirla. */
    val smbSaved: BrowserServer? = null,
    /** "Usa questa cartella" ha senso: non sull'elenco delle condivisioni di un computer. */
    val canUse: Boolean = true,
)

/** Accesso a un computer o NAS con le cartelle condivise (SMB). */
data class SmbLoginState(
    /** Indirizzo scritto o del computer scelto: "192.168.1.20", "nas.local", "\\NAS\Foto". */
    val address: String = "",
    /** Nome del computer trovato in rete, se scelto dall'elenco. */
    val name: String? = null,
    val user: String = "",
    val loading: Boolean = false,
    @StringRes val errorRes: Int? = null,
    /** Il computer non sa elencare le condivisioni: se ne scrive il nome. */
    val askShare: Boolean = false,
)

/** Collegamento di un album condiviso di iCloud: QR per il telefono, ricerca e anteprima dell'album. */
data class ICloudLinkState(
    /** Indirizzo della pagina per incollare il link dal telefono; null se la TV non è in rete. */
    val pairingUrl: String? = null,
    val loading: Boolean = false,
    @StringRes val errorRes: Int? = null,
    /** Album trovato, pronto per "Usa questo album". */
    val token: String? = null,
    val name: String = "",
    val owner: String? = null,
    val photos: Int = 0,
    val videos: Int = 0,
    /** Album già in uso, mostrato nel browser delle cartelle. */
    val savedName: String? = null,
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
    val party: PartyUiState = PartyUiState(),
    val play: PlayUiState = PlayUiState(),
    val browser: BrowserState = BrowserState(),
    val icloud: ICloudLinkState = ICloudLinkState(),
    val smb: SmbLoginState = SmbLoginState(),
    /**
     * Cresce a ogni cambio di foto voluto (successiva, precedente, nuova raccolta). Il cambio
     * automatico si basa su questo e non sull'indice, che si sposta quando arrivano o spariscono
     * foto degli invitati anche se la foto mostrata resta la stessa.
     */
    val slideTick: Int = 0,
    /** Istante (epoch ms) in cui scatterà la pausa automatica, se attiva. */
    val sleepAtMillis: Long? = null,
    /** Cartella della chiavetta USB dove si copiano le foto della festa, se scelta. */
    val usbBackupName: String? = null,
    /** Indirizzo del vecchio telefono usato come telecamera ("192.168.1.20:8080"). */
    val cameraAddress: String = "",
    /** Silent disco: la musica suona nelle cuffie dei telefoni e la TV resta muta. */
    val silentDisco: Boolean = false,
    /** Muro di telefoni in corso: telefoni in fila e foto mandata. */
    val phoneWall: it.fotofesta.tv.guests.PhoneWall.State? = null,
    /** PIN del chiosco da scrivere, con il motivo; null se non serve. */
    val pinRequest: PinPurpose? = null,
    val networkCheck: NetworkCheck = NetworkCheck(),
)
