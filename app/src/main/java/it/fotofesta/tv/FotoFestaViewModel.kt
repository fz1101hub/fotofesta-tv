package it.fotofesta.tv

import android.app.Application
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import it.fotofesta.tv.data.DemoPhotos
import it.fotofesta.tv.data.SettingsRepository
import it.fotofesta.tv.data.UsbPhotoRepository
import it.fotofesta.tv.files.FileBrowser
import it.fotofesta.tv.files.StoragePermissions
import it.fotofesta.tv.files.StorageRoots
import it.fotofesta.tv.files.toUsbPhotoItem
import it.fotofesta.tv.google.GoogleImportFlow
import it.fotofesta.tv.google.LanPhoneConsent
import it.fotofesta.tv.google.GooglePhotosStream
import it.fotofesta.tv.google.HttpStatusException
import it.fotofesta.tv.guests.GuestParty
import it.fotofesta.tv.guests.GuestPhotoStore
import it.fotofesta.tv.guests.HostRemote
import it.fotofesta.tv.guests.PartyCommand
import it.fotofesta.tv.guests.PartyHub
import it.fotofesta.tv.guests.PartyPlay
import it.fotofesta.tv.guests.TimeCapsule
import it.fotofesta.tv.guests.WifiNetwork
import it.fotofesta.tv.guests.guestPhotoId
import it.fotofesta.tv.guests.toPhotoItem
import it.fotofesta.tv.media.BackgroundMusicPlayer
import it.fotofesta.tv.media.Fanfare
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.BrowserEntry
import it.fotofesta.tv.model.BrowserKind
import it.fotofesta.tv.model.BrowserServer
import it.fotofesta.tv.model.BrowserState
import it.fotofesta.tv.model.CollectionInfo
import it.fotofesta.tv.model.Destination
import it.fotofesta.tv.model.FreshPhotos
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.model.GuestsUiState
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PartyShows
import it.fotofesta.tv.model.PartyUiState
import it.fotofesta.tv.model.PlayUiState
import it.fotofesta.tv.model.PhotoLimit
import it.fotofesta.tv.model.PlaybackOrder
import it.fotofesta.tv.model.PrizeDraw
import it.fotofesta.tv.model.SettingsCategory
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.SmbLoginState
import it.fotofesta.tv.model.SourceType
import it.fotofesta.tv.model.UserFacingException
import it.fotofesta.tv.model.combineWithGuests
import it.fotofesta.tv.icloud.ICloudAlbum
import it.fotofesta.tv.icloud.ICloudAlbumException
import it.fotofesta.tv.icloud.ICloudLink
import it.fotofesta.tv.icloud.ICloudPairingTexts
import it.fotofesta.tv.model.ICloudLinkState
import it.fotofesta.tv.network.LinkPairingServer
import it.fotofesta.tv.network.LocalAddress
import it.fotofesta.tv.network.NetworkFolder
import it.fotofesta.tv.network.NetworkMedia
import it.fotofesta.tv.network.NetworkPath
import it.fotofesta.tv.smb.SmbAddress
import it.fotofesta.tv.smb.SmbDiscovery
import it.fotofesta.tv.smb.SmbException
import it.fotofesta.tv.smb.SmbFolder
import it.fotofesta.tv.smb.SmbLocation
import it.fotofesta.tv.smb.SmbLogin
import it.fotofesta.tv.tv.HomeChannel
import it.fotofesta.tv.store.StoreHome
import it.fotofesta.tv.tv.TvFeatures
import it.fotofesta.tv.tv.TvHotspot
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

class FotoFestaViewModel(application: Application) : AndroidViewModel(application) {
    private val context: android.content.Context
        get() = getApplication<Application>().applicationContext
    private val settingsRepository = SettingsRepository(context)
    private val usbRepository = UsbPhotoRepository(context)
    private val fileBrowser = FileBrowser(FileBrowser.imageExtensions(Build.VERSION.SDK_INT))
    private var browserJob: Job? = null
    private var discoveryJob: Job? = null
    private val musicPlayer = BackgroundMusicPlayer(context) {
        showMessage(context.getString(R.string.message_music_error))
    }
    private val services = context.appServices
    private val googleAuth = services.googleAuth
    private val googlePicker = services.googlePicker
    private val googleSelection = services.googleSelection
    private val googleStream = services.googleStream
    private val googleImport = GoogleImportFlow(googleAuth, googlePicker) { LanPhoneConsent(context) }
    private val guests = services.guests
    private val networkMedia = services.networkMedia
    private val smbShares = services.smbShares
    /** Nomi dei computer con cartelle condivise trovati in rete, per mostrarli al posto dell'indirizzo. */
    private val smbNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var smbJob: Job? = null
    private val icloudClient = services.icloudClient
    private val icloudStream = services.icloudStream
    /** Album iCloud trovato e mostrato in anteprima, in attesa di "Usa questo album". */
    private var icloudPreview: ICloudAlbum? = null
    private var icloudJob: Job? = null
    private var icloudRefreshJob: Job? = null
    private var pairingServer: LinkPairingServer? = null
    /** Gioco con i telefoni, per "Foto misteriosa" e "Chi l'ha scattata?" durante la festa. */
    val phoneGame = services.phoneGame
    /** Passaggi di ogni foto sullo schermo, per "Foto che invecchiano". */
    private val showCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var lastShownPhoto: PhotoItem? = null
    private var quietJob: Job? = null
    private var rehearsalJob: Job? = null
    /** Reazioni e dediche dei telefoni durante la festa. */
    private val partyHub = services.partyHub
    /** Giochi con i telefoni oltre ai quiz. */
    private val partyPlay = services.partyPlay
    private val phoneWall = services.phoneWall
    /** Regia dal telefono: i comandi arrivano dai thread del server e si eseguono qui. */
    private val hostRemote = services.hostRemote
    private val _reactions = MutableSharedFlow<PartyHub.ReactionEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Reazioni appena arrivate dai telefoni, da animare sopra lo slideshow. */
    val reactions = _reactions.asSharedFlow()
    private val _arrivals = MutableSharedFlow<PartyHub.Arrival>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Invitati appena arrivati dal QR dell'ingresso: lo slideshow dà il benvenuto con la fanfara. */
    val arrivals = _arrivals.asSharedFlow()
    private val playbackOrder = PlaybackOrder()
    /** Rete Wi‑Fi creata dalla TV per la festa, dove non c'è una rete di casa. */
    private val hotspot = TvHotspot(context)
    /** Vecchio telefono come telecamera e microfono. */
    val ipCamera = services.ipCamera
    /** Una copia sulla chiavetta non riuscita si segnala una volta sola per festa. */
    @Volatile private var usbBackupWarned = false
    private val freshPhotos = FreshPhotos()
    private var googleJob: Job? = null
    private var usbJob: Job? = null
    private var restoreJob: Job? = null
    /** Lettura all'avvio della cartella musicale salvata; annullata se la musica cambia prima. */
    private var musicRestoreJob: Job? = null
    private var disconnectJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepJobMinutes = 0
    /** Video che stanno suonando: intanto la musica di sottofondo si ferma. */
    private val audibleVideos = mutableSetOf<String>()
    private val savedMusic = settingsRepository.loadMusic()
    /** Brani da suonare: quello scelto, o quelli della cartella scelta (letti all'avvio). */
    private var musicTracks: List<String> = savedMusic?.takeIf { !it.folder }?.let { listOf(it.uri) } ?: emptyList()

    private val demoPhotos = DemoPhotos.load(context)

    // Raccolta scelta (demo, USB o Google) e foto approvate degli invitati: insieme formano quella mostrata.
    private var basePhotos: List<PhotoItem> = demoPhotos
    private var baseLabel: String = context.getString(R.string.source_demo)
    private var baseIsDemo = true
    private var baseSource = SourceType.DEMO
    /** Nome della raccolta per la home: cartella USB, Google Foto o foto demo. */
    private var baseTitle: String = context.getString(R.string.collection_demo)
    /** Schermata a cui tornare dalla Wi‑Fi degli invitati (festa o impostazioni). */
    private var wifiReturnTo = Destination.GUESTS
    private var guestPhotos: List<PhotoItem> = emptyList()
    private var guestPartyActive = false
    /** Foto approvate già viste; null finché le foto di una festa interrotta non sono state riprese. */
    private var knownGuestIds: Set<String>? = null

    private val _state = MutableStateFlow(
        AppState(
            photos = demoPhotos,
            sourceLabel = context.getString(R.string.source_demo),
            collection = CollectionInfo(SourceType.DEMO, context.getString(R.string.collection_demo), demoPhotos.size),
            settings = settingsRepository.load(),
            musicUri = savedMusic?.uri,
            musicName = savedMusic?.let { saved -> saved.name.ifBlank { context.getString(R.string.music_default_name) } },
            usbBackupName = settingsRepository.loadUsbBackup()?.second,
            cameraAddress = settingsRepository.loadCameraAddress(),
        )
    )
    val state = _state.asStateFlow()

    init {
        playbackOrder.reset(demoPhotos.size, _state.value.settings.shuffle)
        savedMusic?.takeIf { it.folder }?.let { saved ->
            musicRestoreJob = viewModelScope.launch {
                val path = Uri.parse(saved.uri).path ?: return@launch
                val tracks = folderTracks(path)
                // Nel frattempo la musica può essere stata tolta o cambiata dalle impostazioni.
                if (_state.value.musicUri != saved.uri) return@launch
                musicTracks = tracks
                if (tracks.isNotEmpty()) _state.update { it.copy(musicName = folderMusicName(saved.name, tracks.size)) }
                syncMusic()
            }
        }
        val lastSource = settingsRepository.loadLastSource()
        if (lastSource == "GOOGLE") {
            restoreJob = viewModelScope.launch {
                val selection = withContext(Dispatchers.IO) { googleSelection.current() }
                when {
                    selection != null -> showPhotos(selection.toPhotos(), googleLabel(selection.items.size), SourceType.GOOGLE_PHOTOS, googleTitle())
                    // Già spiegato da onGoogleSelectionGone (es. accesso perso durante il salvaschermo).
                    googleStream.gone.value != null -> Unit
                    else -> {
                        // Selezione scaduta o salvata dalle versioni che scaricavano le foto: va rifatta.
                        settingsRepository.saveLastSource("DEMO")
                        showMessage(context.getString(R.string.error_google_selection_gone))
                    }
                }
                // La scadenza indicata da Google si verifica con Google, non con l'orologio della TV,
                // che all'accensione può essere sbagliato: lo fa GooglePhotosStream al primo caricamento.
            }
        } else {
            // Selezione rimasta da una sorgente abbandonata: si chiude la sessione. Lavoro separato da
            // restoreJob, che il ripristino della cartella USB annulla subito.
            viewModelScope.launch {
                withContext(Dispatchers.IO) { googleSelection.current() }
                if (settingsRepository.loadLastSource() != "GOOGLE") discardGoogleSelection()
            }
        }
        if (lastSource == "USB") {
            val folder = settingsRepository.loadUsbFolder()
            if (folder != null) loadUsbFolder(folder) else settingsRepository.loadUsbTree()?.let { stored -> loadUsb(Uri.parse(stored)) }
        }
        if (lastSource == "NETWORK") settingsRepository.loadNetworkFolder()?.let { loadNetworkFolder(it) }
        if (lastSource == "SMB") settingsRepository.loadSmbFolder()?.let { loadSmbFolder(it, settingsRepository.loadSmbPassword()) }
        if (lastSource == "ICLOUD") settingsRepository.loadICloudToken()?.let { restoreICloudAlbum(it) }
        _state.update { it.copy(icloud = it.icloud.copy(savedName = settingsRepository.loadICloudName())) }
        viewModelScope.launch {
            googleStream.gone.collect { gone -> if (gone != null) onGoogleSelectionGone(gone) }
        }
        viewModelScope.launch {
            combine(guests.state, guests.store.photos, guests.ready) { party, photos, ready -> Triple(party, photos, ready) }
                .collect { (party, photos, ready) -> onGuestsChanged(party, photos, ready) }
        }
        partyHub.onChange = ::refreshParty
        partyPlay.onChange = ::refreshPlay
        phoneWall.onChange = { _state.update { it.copy(phoneWall = phoneWall.state()) } }
        partyHub.onArrival = { arrival ->
            _arrivals.tryEmit(arrival)
            Fanfare.play()
            // Fuori dallo slideshow il benvenuto è un messaggio in basso.
            if (_state.value.destination != Destination.SLIDESHOW) {
                showMessage(context.getString(R.string.arrival_message, arrival.name, arrival.order))
            }
        }
        partyHub.onReaction = {
            _reactions.tryEmit(it)
            guests.saveHubSoon()
        }
        val runOnMain: (PartyCommand) -> Unit = { command -> viewModelScope.launch { execute(command) } }
        hostRemote.onCommand = runOnMain
        partyHub.onDirectorCommand = runOnMain
        hostRemote.setEnabled(_state.value.settings.phoneHost)
        syncPartyRules()
        syncPartyFeatures()
        syncTvServices()
        refreshParty()
        // La pagina di regia mostra cosa c'è sullo schermo e cosa aspetta l'approvazione.
        viewModelScope.launch {
            _state.map { state ->
                HostRemote.Status(
                    slideshow = state.destination == Destination.SLIDESHOW,
                    playing = state.isPlaying,
                    title = state.photos.getOrNull(state.currentIndex)?.title,
                    photos = state.photos.size,
                    pendingPhotos = state.guests.pending.mapNotNull { photo -> photo.guestPhotoId?.let { HostRemote.PendingPhoto(it, photo.sender) } },
                )
            }
                .distinctUntilChanged()
                .collect { hostRemote.updateStatus(it) }
        }
        // Le reazioni dei telefoni contano per la foto sullo schermo; con "Festa senza tracce" la foto
        // degli invitati appena lasciata si cancella, con "Foto che invecchiano" si contano i passaggi.
        viewModelScope.launch {
            _state.map { state -> state.photos.getOrNull(state.currentIndex).takeIf { state.destination == Destination.SLIDESHOW } }
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collect { photo ->
                    partyHub.showing(photo?.id)
                    val previous = lastShownPhoto
                    lastShownPhoto = photo
                    if (photo != null) showCounts[photo.id] = (showCounts[photo.id] ?: 0) + 1
                    if (_state.value.settings.noTraces && previous != null && previous.id != photo?.id) {
                        previous.guestPhotoId?.let(guests::reject)
                    }
                }
        }
    }

    /** Chiamato anche dai thread del server della festa: lo stato si aggiorna in modo atomico. */
    private fun refreshParty() {
        val quietLeft = partyHub.quietLeftMillis()
        val developed = partyHub.isDeveloped()
        val party = PartyUiState(
            messages = partyHub.messages(),
            pendingMessages = partyHub.pendingMessages(),
            announcement = partyHub.announcement(),
            director = partyHub.director(),
            hostPin = hostRemote.pin(),
            quietUntilMillis = if (quietLeft > 0) System.currentTimeMillis() + quietLeft else 0,
            undeveloped = !developed,
            shotsTaken = partyHub.photosTaken(),
            rehearsal = rehearsalJob?.isActive == true,
            arrivals = partyHub.arrivals().map { it.name },
        )
        val wasUndeveloped = _state.value.party.undeveloped
        _state.update { it.copy(party = party) }
        guests.saveHubSoon()
        // Il rullino appena sviluppato (o la regola cambiata) cambia le foto in mostra.
        if (wasUndeveloped != !developed) viewModelScope.launch { applyCollection(restart = false) }
    }

    /** Regole delle foto degli invitati, ripresa della festa e copia nella galleria. */
    private fun syncPartyRules() {
        val settings = _state.value.settings
        partyHub.setPhotoRules(PartyHub.PhotoRules(settings.photoLimit.perGuest, settings.photoLimit == PhotoLimit.DISPOSABLE))
        guests.store.durable = settings.resumeParty && !settings.noTraces
        val gallery = settings.backupCopy && !settings.noTraces && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        // "Festa senza tracce" vale anche per la chiavetta.
        val usb = settingsRepository.loadUsbBackup()?.first?.takeIf { !settings.noTraces }?.let(Uri::parse)
        guests.onPhotoSaved = if (gallery || usb != null) {
            { bytes, sender ->
                viewModelScope.launch(Dispatchers.IO) {
                    if (gallery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) backupPhoto(bytes, sender)
                    if (usb != null) usbBackupPhoto(usb, bytes, sender)
                }
            }
        } else {
            null
        }
    }

    /** Schermo DLNA e canale della schermata Home, come chiedono le impostazioni. */
    private fun syncTvServices() {
        val settings = _state.value.settings
        guests.setDlna(settings.dlnaReceiver)
        viewModelScope.launch(Dispatchers.IO) {
            // Fire TV non mostra i canali di Android TV: nella versione Amazon usa le raccomandazioni.
            if (TvFeatures.isFireTv(context)) {
                val home = StoreHome(context)
                if (settings.homeChannel) home.publish() else home.remove()
                return@launch
            }
            val channel = HomeChannel(context)
            if (settings.homeChannel) channel.publish() else channel.remove()
        }
    }

    /** Nome del file di una copia: data e ora, poi l'autore senza caratteri strani. */
    private fun backupName(now: Long, sender: String?): String = buildString {
        append("festa_").append(now)
        sender?.let { append('_').append(it.filter { char -> char.isLetterOrDigit() }.take(20)) }
        append(".jpg")
    }

    /** Copia sulla chiavetta USB, in FotoFesta/<data> dentro la cartella scelta. */
    private fun usbBackupPhoto(tree: Uri, bytes: ByteArray, sender: String?) {
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(now))
        runCatching {
            val root = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Chiavetta non trovata")
            val app = root.findFile(USB_BACKUP_FOLDER)?.takeIf { it.isDirectory } ?: root.createDirectory(USB_BACKUP_FOLDER)
            val folder = app?.findFile(day)?.takeIf { it.isDirectory } ?: app?.createDirectory(day)
            val file = folder?.createFile("image/jpeg", backupName(now, sender)) ?: throw IOException("File non creato")
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) } ?: throw IOException("File non scritto")
        }.onFailure {
            Log.w(TAG, "Copia sulla chiavetta non riuscita", it)
            if (!usbBackupWarned) {
                usbBackupWarned = true
                viewModelScope.launch { showMessage(context.getString(R.string.message_usb_backup_failed)) }
            }
        }
    }

    /**
     * Capsula del tempo sulla chiavetta: foto approvate, dediche, propositi della capsula e chi è
     * arrivato, in una pagina che si apre tra un anno (ma si può sbirciare).
     */
    fun sealCapsule() {
        if (_state.value.busy) return
        if (_state.value.settings.noTraces) {
            showMessage(context.getString(R.string.message_capsule_no_traces))
            return
        }
        val tree = settingsRepository.loadUsbBackup()?.first?.let(Uri::parse)
        if (tree == null) {
            showMessage(context.getString(R.string.message_capsule_no_usb))
            return
        }
        val photos = guests.store.photos.value.filter { it.approved }.sortedBy { it.receivedAtMillis }
        val messages = partyHub.messages().map { TimeCapsule.Note(it.text, it.name) }
        val wishes = partyPlay.current()?.takeIf { it.kind == PartyPlay.Kind.CAPSULE }?.entries
            ?.mapNotNull { entry -> entry.text?.let { TimeCapsule.Note(it, entry.name) } }.orEmpty()
        val arrivals = partyHub.arrivals().map { it.name }
        if (photos.isEmpty() && messages.isEmpty() && wishes.isEmpty()) {
            showMessage(context.getString(R.string.message_capsule_empty))
            return
        }
        _state.update { it.copy(busy = true, message = context.getString(R.string.message_capsule_writing)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { writeCapsule(tree, photos, messages, wishes, arrivals) } }
            _state.update { it.copy(busy = false) }
            result.onSuccess { opens -> showMessage(context.getString(R.string.message_capsule_done, opens)) }
                .onFailure {
                    Log.w(TAG, "Capsula del tempo non scritta", it)
                    showMessage(context.getString(R.string.message_capsule_failed))
                }
        }
    }

    /** Scrive la capsula in FotoFesta/<capsula e data> e restituisce la data di apertura, già scritta. */
    private fun writeCapsule(
        tree: Uri,
        photos: List<GuestPhotoStore.Photo>,
        messages: List<TimeCapsule.Note>,
        wishes: List<TimeCapsule.Note>,
        arrivals: List<String>,
    ): String {
        val now = System.currentTimeMillis()
        val openAt = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            add(java.util.Calendar.YEAR, 1)
        }.timeInMillis
        val dateFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG)
        val root = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Chiavetta non trovata")
        val app = root.findFile(USB_BACKUP_FOLDER)?.takeIf { it.isDirectory } ?: root.createDirectory(USB_BACKUP_FOLDER)
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.ROOT).format(java.util.Date(now))
        val folder = app?.createDirectory(context.getString(R.string.capsule_folder, stamp)) ?: throw IOException("Cartella non creata")
        val pictures = folder.createDirectory(CAPSULE_PHOTOS) ?: throw IOException("Cartella non creata")
        val written = photos.mapIndexedNotNull { index, photo ->
            val bytes = guests.store.read(photo.id) ?: return@mapIndexedNotNull null
            val name = String.format(java.util.Locale.ROOT, "%03d.jpg", index + 1)
            val file = pictures.createFile("image/jpeg", name) ?: throw IOException("File non creato")
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) } ?: throw IOException("File non scritto")
            TimeCapsule.Photo("$CAPSULE_PHOTOS/${file.name ?: name}", photo.sender)
        }
        val opens = dateFormat.format(java.util.Date(openAt))
        val html = TimeCapsule.html(
            language = java.util.Locale.getDefault().toLanguageTag(),
            title = context.getString(_state.value.settings.template.titleRes),
            sealedText = dateFormat.format(java.util.Date(now)),
            openText = opens,
            openAtMillis = openAt,
            photos = written,
            messages = messages,
            wishes = wishes,
            arrivals = arrivals,
            texts = TimeCapsule.Texts(
                kicker = context.getString(R.string.capsule_html_kicker),
                sealedOn = context.getString(R.string.capsule_html_sealed),
                opensOn = context.getString(R.string.capsule_html_opens),
                daysOne = context.getString(R.string.capsule_html_day),
                daysOther = context.getString(R.string.capsule_html_days),
                peek = context.getString(R.string.capsule_html_peek),
                photos = context.getString(R.string.capsule_html_photos),
                messages = context.getString(R.string.capsule_html_messages),
                wishes = context.getString(R.string.capsule_html_wishes),
                arrivals = context.getString(R.string.capsule_html_arrivals),
            ),
        )
        val page = folder.createFile("text/html", context.getString(R.string.capsule_file) + ".html") ?: throw IOException("File non creato")
        context.contentResolver.openOutputStream(page.uri)?.use { it.write(html.toByteArray(Charsets.UTF_8)) } ?: throw IOException("File non scritto")
        return opens
    }

    /** Cartella della chiavetta scelta per la copia delle foto della festa. */
    fun setUsbBackup(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }.isFailure) {
            showMessage(context.getString(R.string.message_usb_backup_failed))
            return
        }
        val name = DocumentFile.fromTreeUri(context, tree)?.name ?: context.getString(R.string.settings_usb_backup_default)
        settingsRepository.saveUsbBackup(tree.toString(), name)
        usbBackupWarned = false
        _state.update { it.copy(usbBackupName = name) }
        syncPartyRules()
        showMessage(context.getString(R.string.message_usb_backup_on, name))
    }

    fun removeUsbBackup() {
        settingsRepository.loadUsbBackup()?.first?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        settingsRepository.saveUsbBackup(null, null)
        _state.update { it.copy(usbBackupName = null) }
        syncPartyRules()
    }

    /**
     * Rete della TV: la TV crea una sua Wi‑Fi (senza internet) e il QR della Wi‑Fi la mostra.
     * Chiamato dopo che l'attività ha ottenuto il permesso di Android per i dispositivi Wi‑Fi vicini.
     */
    fun toggleHotspot() {
        if (hotspot.active || _state.value.guests.hotspot) {
            stopHotspot()
            return
        }
        hotspot.start(
            started = { network ->
                guests.useHotspot(WifiNetwork(network.ssid, network.password), network.address)
                showMessage(context.getString(R.string.message_hotspot_on, network.ssid))
            },
            failed = { showMessage(context.getString(R.string.message_hotspot_failed)) },
        )
    }

    private fun stopHotspot() {
        hotspot.stop()
        guests.useHotspot(null, null)
    }

    fun showHotspotPermissionDenied() = showMessage(context.getString(R.string.message_hotspot_permission))

    fun saveCameraAddress(address: String) {
        val value = address.trim()
        settingsRepository.saveCameraAddress(value)
        _state.update { it.copy(cameraAddress = value) }
    }

    /** Foto scattata dalla telecamera del vecchio telefono: entra nella festa come le altre. */
    fun addCameraPhoto(bytes: ByteArray) {
        if (!_state.value.guests.active) return
        guests.addPhoto(bytes, context.getString(R.string.camera_sender))
        showMessage(context.getString(R.string.message_camera_photo))
    }

    /** Scorciatoie del canale nella Home della TV e avvio automatico all'accensione. */
    fun openLink(route: String) {
        when (route) {
            "slideshow" -> playCurrent()
            "party" -> navigate(Destination.GUESTS)
            "games" -> navigate(Destination.GAMES)
            "shows" -> navigate(Destination.SHOWS)
            else -> navigate(Destination.HOME)
        }
    }

    /** Copia di una foto degli invitati in Immagini/FotoFesta/<data>, nella galleria della TV. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun backupPhoto(bytes: ByteArray, sender: String?) {
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(now))
        val name = backupName(now, sender)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/FotoFesta/$day")
        }
        runCatching {
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
        }.onFailure { Log.w(TAG, "Copia di sicurezza non riuscita", it) }
    }

    /** Quante volte la foto è passata sullo schermo e quanto è "invecchiata" (0 nuova, 1 molto). */
    fun photoAge(photo: PhotoItem): Float {
        if (!_state.value.settings.agingPhotos) return 0f
        val shows = (showCounts[photo.id] ?: 1) - 1
        val reactions = partyHub.reactionCount(photo.id)
        return ((shows - 2 * reactions).coerceIn(0, AGING_STEPS)).toFloat() / AGING_STEPS
    }

    /** Sviluppo del rullino usa e getta: le foto scattate finora compaiono. */
    fun develop() {
        partyHub.develop()
        showMessage(context.getString(R.string.message_developed))
    }

    /** Tre minuti senza telefoni: lo slideshow si ferma su una foto e i telefoni aspettano. */
    fun startQuiet() {
        if (!_state.value.guests.active) return
        partyHub.startQuiet()
        quietJob?.cancel()
        playCurrent()
        pause()
        quietJob = viewModelScope.launch {
            delay(PartyHub.QUIET_MS)
            partyHub.stopQuiet()
            if (_state.value.destination == Destination.SLIDESHOW && !_state.value.isPlaying) togglePlayback()
        }
    }

    /**
     * Prova generale: per due minuti otto telefoni finti mandano foto demo, reazioni, dediche e
     * risposte, così si provano schermo e audio prima della festa. Alla fine si cancella tutto.
     */
    fun startRehearsal() {
        if (_state.value.guests.active) {
            showMessage(context.getString(R.string.message_rehearsal_party))
            return
        }
        guests.start()
        rehearsalJob?.cancel()
        rehearsalJob = viewModelScope.launch {
            refreshParty()
            // La festa parte sul thread della festa: si aspetta che sia attiva.
            withTimeoutOrNull(5_000) { while (!_state.value.guests.active) delay(100) }
            playCurrent()
            showMessage(context.getString(R.string.message_rehearsal_started))
            val names = listOf("Giulia", "Marco", "Sara", "Luca", "Anna", "Paolo", "Elena", "Davide")
            val players = names.map { "prova-${it.lowercase()}-telefono" }
            val texts = listOf(R.string.rehearsal_message_1, R.string.rehearsal_message_2, R.string.rehearsal_message_3).map { context.getString(it) }
            val pictures = listOf(R.drawable.demo_coast, R.drawable.demo_picnic, R.drawable.demo_mountains, R.drawable.demo_birthday)
            val end = System.currentTimeMillis() + REHEARSAL_MS
            var step = 0
            while (System.currentTimeMillis() < end && _state.value.guests.active) {
                val guest = step % names.size
                when (step % 4) {
                    0 -> withContext(Dispatchers.IO) {
                        runCatching { context.resources.openRawResource(pictures[(step / 4) % pictures.size]).use { it.readBytes() } }.getOrNull()
                    }?.let { guests.addRehearsalPhoto(it, names[guest]) }
                    1 -> repeat(3) { partyHub.react(players[(guest + it) % names.size], kotlin.random.Random.nextInt(PartyHub.Reaction.entries.size), names[(guest + it) % names.size]) }
                    2 -> partyHub.message(players[guest], texts[step % texts.size], names[guest])
                    else -> phoneGame.current()?.takeIf { it.open && it.choices.isNotEmpty() }?.let { question ->
                        phoneGame.answer(question.round, kotlin.random.Random.nextInt(question.choices.size), players[guest], names[guest])
                    }
                }
                step++
                delay(kotlin.random.Random.nextLong(2_000, 4_500))
            }
            if (_state.value.guests.active) endGuestParty()
            showMessage(context.getString(R.string.message_rehearsal_done))
        }
        rehearsalJob?.invokeOnCompletion { viewModelScope.launch { refreshParty() } }
    }

    /** Comando della regia dal telefono o del regista di turno. */
    private fun execute(command: PartyCommand) {
        val inSlideshow = _state.value.destination == Destination.SLIDESHOW
        when (command) {
            PartyCommand.Next -> if (inSlideshow) next() else playCurrent()
            PartyCommand.Previous -> if (inSlideshow) previous()
            PartyCommand.Play -> if (!inSlideshow) playCurrent() else if (!_state.value.isPlaying) togglePlayback()
            PartyCommand.Pause -> if (inSlideshow) pause()
            is PartyCommand.ApprovePhoto -> guests.approve(command.id)
            is PartyCommand.RejectPhoto -> guests.reject(command.id)
            is PartyCommand.ApproveMessage -> partyHub.approveMessage(command.id)
            is PartyCommand.RejectMessage -> partyHub.removeMessage(command.id)
            PartyCommand.ApproveAll -> {
                guests.approveAll()
                partyHub.approveAllMessages()
            }
            is PartyCommand.Announce -> partyHub.announce(command.text)
            PartyCommand.StartDirector -> startDirector()
            PartyCommand.StartQuiet -> startQuiet()
            PartyCommand.Develop -> develop()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Giochi con i telefoni: disegni, frasi, missioni, talpa, torneo, scommesse, caccia al QR
    // -----------------------------------------------------------------------------------------

    /** Chiamato anche dai thread del server: lo stato si aggiorna in modo atomico. */
    private fun refreshPlay() {
        val treasure = partyPlay.treasure()
        val url = _state.value.guests.url
        val play = PlayUiState(
            game = partyPlay.current(),
            treasureUrl = if (treasure != null && url != null) "$url&t=$treasure" else null,
            treasureFinders = partyPlay.treasureFinders(),
            lights = partyPlay.lights(),
            coins = partyPlay.leaderboard(),
        )
        _state.update { it.copy(play = play) }
    }

    /** Foto da rifare in "Ricrea la foto". */
    var recreateOriginal: PhotoItem? = null
        private set

    /** Nuovo gioco [kind] con i testi tradotti; false (con un messaggio) se mancano invitati con il nome. */
    fun startPlay(kind: PartyPlay.Kind): Boolean {
        fun texts(id: Int) = context.getString(id).split('|').map(String::trim).filter(String::isNotEmpty).shuffled()
        val people = partyHub.participants()
        val (prompt, pool) = when (kind) {
            PartyPlay.Kind.DRAW_GUESS -> context.getString(R.string.play_prompt_draw_guess) to texts(R.string.play_words)
            PartyPlay.Kind.DRAWING_WALL -> texts(R.string.play_wall_themes).first() to emptyList<String>()
            PartyPlay.Kind.STORY -> context.getString(R.string.play_prompt_story) to texts(R.string.play_story_starts)
            PartyPlay.Kind.WHISPER -> context.getString(R.string.play_prompt_whisper) to texts(R.string.play_whisper_phrases)
            PartyPlay.Kind.COMPLIMENTS -> context.getString(R.string.play_prompt_compliments) to emptyList<String>()
            PartyPlay.Kind.MISSIONS -> context.getString(R.string.play_prompt_missions) to texts(R.string.play_missions)
            PartyPlay.Kind.PAPARAZZI -> context.getString(R.string.play_prompt_paparazzi) to emptyList<String>()
            PartyPlay.Kind.SAME_MOMENT -> context.getString(R.string.play_prompt_moment) to emptyList<String>()
            PartyPlay.Kind.RECREATE -> {
                val original = _state.value.photos.filterNot { it.video }.randomOrNull()
                recreateOriginal = original
                if (original == null) {
                    showMessage(context.getString(R.string.games_need_photos, 1))
                    return false
                }
                context.getString(R.string.play_prompt_recreate) to listOf(original.id)
            }
            PartyPlay.Kind.MOLE -> context.getString(R.string.play_prompt_mole) to texts(R.string.play_mole_missions)
            PartyPlay.Kind.CAPSULE -> context.getString(R.string.play_prompt_capsule) to emptyList<String>()
        }
        if (partyPlay.start(kind, prompt, pool) == null) {
            showMessage(context.getString(if (kind == PartyPlay.Kind.COMPLIMENTS && people.isNotEmpty()) R.string.play_need_two else R.string.play_need_names))
            return false
        }
        navigate(Destination.PLAY)
        return true
    }

    fun playReveal() = partyPlay.reveal()

    fun playVoting() = partyPlay.startVoting()

    /** Stesso gioco, nuovo turno (nuova parola, nuova talpa, nuova star). */
    fun playAgain() {
        val kind = partyPlay.current()?.kind ?: return
        startPlay(kind)
    }

    /** Foto degli invitati arrivate nel gioco in corso (missioni, paparazzi, stesso istante, ricrea). */
    fun playPhotos(round: String): List<PhotoItem> {
        val byId = guestPhotos.associateBy { it.guestPhotoId }
        return partyPlay.taggedPhotos(round).mapNotNull { byId[it] }
    }

    /** Didascalia della foto nello slideshow se è nata da un gioco ("Missione: un brindisi"). */
    fun photoTag(photo: PhotoItem): String? {
        val tag = photo.guestPhotoId?.let(partyPlay::tag) ?: return null
        return when (tag.kind) {
            PartyPlay.Kind.MISSIONS -> tag.text?.let { context.getString(R.string.tag_mission, it) }
            PartyPlay.Kind.PAPARAZZI -> tag.text?.let { context.getString(R.string.tag_paparazzi, it) }
            PartyPlay.Kind.SAME_MOMENT -> context.getString(R.string.tag_moment)
            PartyPlay.Kind.RECREATE -> context.getString(R.string.tag_recreate)
            else -> null
        }
    }

    /** Caccia al QR: il codice compare per trenta secondi sullo schermo. */
    fun startTreasure() {
        if (!_state.value.guests.active) return
        partyPlay.startTreasure()
        refreshPlay()
        // Allo scadere il QR sparisce anche se nessuno l'ha trovato.
        viewModelScope.launch {
            delay(PartyPlay.TREASURE_MS + 500)
            refreshPlay()
        }
    }

    fun setLights(lights: PartyPlay.Lights?) = partyPlay.setLights(lights)

    /** Muro di telefoni: i telefoni si mettono in fila, poi la TV manda una foto degli invitati. */
    fun startPhoneWall() {
        if (!_state.value.guests.active) return
        phoneWall.start()
        navigate(Destination.PHONE_WALL)
    }

    /** Manda ai telefoni in fila una foto degli invitati, diversa da quella di prima se ce n'è un'altra. */
    fun showPhoneWall() {
        val ids = guestPhotos.filterNot { it.video }.mapNotNull { it.guestPhotoId }
        if (ids.isEmpty()) {
            showMessage(context.getString(R.string.message_phone_wall_no_photos))
            return
        }
        val previous = phoneWall.state()?.photoId
        val next = ids.filter { it != previous }.randomOrNull() ?: ids.first()
        if (!phoneWall.show(next)) showMessage(context.getString(R.string.message_phone_wall_nobody))
    }

    fun rearrangePhoneWall() = phoneWall.rearrange()

    /** Scommessa decisa: chi ha scelto [choice] vince tre monete. Restituisce quanti hanno vinto. */
    fun settleBet(choice: Int): Int {
        val winners = phoneGame.voters(choice)
        winners.forEach { partyPlay.award(it, BET_COINS) }
        phoneGame.stop()
        return winners.size
    }

    /** Roulette dei brindisi: il nome estratto compare anche sui telefoni come annuncio. */
    fun announceToast(name: String) {
        partyHub.announce(context.getString(R.string.toast_winner, name))
    }

    // -----------------------------------------------------------------------------------------
    // Spettacoli sullo schermo
    // -----------------------------------------------------------------------------------------

    /** La festa in numeri. */
    fun partyStats(): PartyShows.Stats = PartyShows.stats(
        photos = _state.value.photos,
        messages = partyHub.messages().size,
        reactionsByPhoto = partyHub.reactionCounts(),
        reactionsByKind = partyHub.reactionTotals(),
        coins = partyPlay.leaderboard(Int.MAX_VALUE).sumOf { it.coins },
    )

    /** Titoli di coda: fotografi, dediche, campioni dei giochi e gli altri invitati. */
    fun credits(): List<PartyShows.CreditSection> = PartyShows.credits(
        photoSenders = guestPhotos.map { it.sender },
        messageNames = partyHub.messages().map { it.name },
        champions = (phoneGame.leaderboard(3).map { it.name } + partyPlay.leaderboard(3).map { it.name }),
        participants = lotteryCandidates(),
    )

    /** Modello di festa: applica titolo, transizione, disposizione, filtro ed effetti. */
    fun applyTemplate(template: it.fotofesta.tv.model.PartyTemplate) {
        updateSettings(template.apply(_state.value.settings))
        showMessage(context.getString(R.string.template_applied, context.getString(template.labelRes)))
    }

    /** Annuncio dalla TV (schermata "Fai un annuncio"). */
    fun announce(text: String): Boolean {
        val shown = partyHub.announce(text)
        if (shown) navigate(Destination.GUESTS) else showMessage(context.getString(R.string.announce_empty))
        return shown
    }

    /** Regista per due minuti: un invitato con il nome dirige lo slideshow dal suo telefono. */
    fun startDirector() {
        val director = partyHub.startDirector()
        if (director == null) {
            showMessage(context.getString(R.string.message_director_none))
            return
        }
        playCurrent()
        showMessage(context.getString(R.string.message_director_started, director.name))
    }

    private fun syncPartyFeatures() {
        val settings = _state.value.settings
        partyHub.setFeatures(
            PartyHub.Features(
                reactions = settings.phoneReactions,
                messages = settings.phoneMessages,
                // Con l'approvazione delle foto anche le dediche aspettano il via libera.
                moderateMessages = _state.value.guests.approval,
            ),
        )
    }

    fun approveMessage(message: PartyHub.Message) = partyHub.approveMessage(message.id)

    fun discardMessage(message: PartyHub.Message) = partyHub.removeMessage(message.id)

    fun approveAllMessages() = partyHub.approveAllMessages()

    /** Reazioni ricevute da ogni foto, per il premio "Foto della serata". */
    fun reactionCounts(): Map<String, Int> = partyHub.reactionCounts()

    /** Chi può vincere all'estrazione: nomi scritti con foto, giochi, reazioni e dediche. */
    fun lotteryCandidates(): List<String> = PrizeDraw.candidates(
        partyHub.participants().map { it.name },
        phoneGame.names(),
        guestPhotos.sortedBy { it.receivedAtMillis ?: 0L }.map { it.sender },
    )

    fun navigate(destination: Destination) {
        _state.update { it.copy(destination = destination, message = null) }
    }

    /** Azione del tasto Indietro, coerente con i pulsanti "Indietro" delle singole schermate. */
    fun back() {
        when (_state.value.destination) {
            Destination.HOME -> Unit
            Destination.SLIDESHOW -> {
                pause()
                navigate(Destination.HOME)
            }
            Destination.MEMORY, Destination.MYSTERY, Destination.PUZZLE, Destination.WHO_TOOK_IT,
            Destination.POLL, Destination.LOTTERY, Destination.PARTY_GAMES -> navigate(Destination.GAMES)
            Destination.PLAY -> {
                partyPlay.stop()
                navigate(Destination.PARTY_GAMES)
            }
            Destination.TOURNAMENT, Destination.BETS -> {
                phoneGame.stop()
                navigate(Destination.PARTY_GAMES)
            }
            Destination.TOAST, Destination.COINS -> navigate(Destination.PARTY_GAMES)
            Destination.SHOWS, Destination.BUZZER, Destination.CAMERA -> navigate(Destination.GAMES)
            Destination.REWIND, Destination.BARCODE, Destination.PHOTO_MOSAIC, Destination.CREDITS,
            Destination.TELETEXT, Destination.SKY, Destination.STATS -> navigate(Destination.SHOWS)
            Destination.GOOGLE_CONNECT -> cancelGoogleConnect()
            Destination.SETTINGS, Destination.GAMES, Destination.GUESTS -> navigate(Destination.HOME)
            Destination.EXTRA_SCREEN, Destination.ANYWHERE -> navigate(Destination.GUESTS)
            Destination.PHONE_WALL -> {
                phoneWall.stop()
                navigate(Destination.GAMES)
            }
            Destination.GUEST_REVIEW, Destination.AWARDS, Destination.GUESTBOOK, Destination.ANNOUNCE, Destination.DOOR -> navigate(Destination.GUESTS)
            Destination.GUEST_WIFI -> navigate(wifiReturnTo)
            Destination.FILE_BROWSER -> if (_state.value.browser.directory != null) browserUp() else closeBrowser()
            Destination.ICLOUD_LINK -> closeICloudLink()
            Destination.SMB_LOGIN -> closeSmbLogin()
        }
    }

    fun useDemoAndPlay() {
        cancelPendingSources()
        settingsRepository.saveLastSource("DEMO")
        showDemoPhotos()
        discardGoogleSelection()
        _state.update { it.copy(destination = Destination.SLIDESHOW, isPlaying = true) }
        syncMusic()
    }

    fun playCurrent() {
        // Durante la festa lo slideshow parte anche vuoto: mostra il QR in attesa della prima foto.
        if (_state.value.photos.isEmpty() && !_state.value.guests.active) {
            showMessage(context.getString(R.string.message_choose_source))
            return
        }
        _state.update { it.copy(destination = Destination.SLIDESHOW, isPlaying = true) }
        syncMusic()
    }

    fun togglePlayback() {
        _state.update { it.copy(isPlaying = !it.isPlaying) }
        syncMusic()
    }

    fun pause() {
        _state.update { it.copy(isPlaying = false) }
        syncMusic()
    }

    // PlaybackOrder ha effetti collaterali (rimescola a fine giro): l'indice va calcolato fuori da
    // update {}, il cui blocco può essere rieseguito in caso di scritture concorrenti.
    // Le foto appena mandate dagli invitati passano davanti, poi si riprende dal punto lasciato.
    fun next() {
        val current = _state.value
        if (current.photos.isEmpty()) return
        val index = freshPhotos.take(current.photos, current.currentIndex)
            ?: freshPhotos.resume(current.photos, playbackOrder::next)
            ?: playbackOrder.next(current.currentIndex)
        _state.update { it.copy(currentIndex = index, slideTick = it.slideTick + 1) }
    }

    fun previous() {
        val current = _state.value
        if (current.photos.isEmpty()) return
        val index = freshPhotos.takeResume(current.photos) ?: playbackOrder.previous(current.currentIndex)
        _state.update { it.copy(currentIndex = index, slideTick = it.slideTick + 1) }
    }

    /** Foto successiva prevista, usata dallo slideshow per precaricarla. */
    fun upcomingPhoto(): PhotoItem? {
        val current = _state.value
        freshPhotos.peek(current.photos, current.currentIndex)?.let { return current.photos[it] }
        val from = freshPhotos.peekResume(current.photos) ?: current.currentIndex
        return playbackOrder.peekNext(from)?.let(current.photos::getOrNull)
    }

    fun updateSettings(value: SlideshowSettings) {
        val previous = _state.value.settings
        settingsRepository.save(value)
        _state.update { it.copy(settings = value) }
        if (previous.shuffle != value.shuffle) {
            playbackOrder.reset(_state.value.photos.size, value.shuffle, _state.value.currentIndex)
        }
        musicPlayer.setVolume(musicVolume())
        if (previous.sleepTimerMinutes != value.sleepTimerMinutes) syncSleepTimer()
        if (previous.videos != value.videos) applyCollection(restart = false)
        if (previous.phoneReactions != value.phoneReactions || previous.phoneMessages != value.phoneMessages) syncPartyFeatures()
        if (previous.photoLimit != value.photoLimit || previous.resumeParty != value.resumeParty ||
            previous.noTraces != value.noTraces || previous.backupCopy != value.backupCopy
        ) {
            syncPartyRules()
            refreshParty()
        }
        if (previous.phoneHost != value.phoneHost) {
            hostRemote.setEnabled(value.phoneHost)
            refreshParty()
        }
        if (previous.dlnaReceiver != value.dlnaReceiver || previous.homeChannel != value.homeChannel) syncTvServices()
    }

    fun loadUsb(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        cancelPendingSources()
        usbJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, message = context.getString(R.string.message_usb_indexing)) }
            try {
                val photos = usbRepository.scan(treeUri)
                if (photos.isEmpty()) {
                    _state.update { it.copy(busy = false, message = context.getString(R.string.message_usb_empty)) }
                } else {
                    settingsRepository.saveUsbTree(treeUri.toString())
                    showPhotos(photos, quantityString(R.plurals.source_usb, photos.size), SourceType.USB, usbTreeTitle(treeUri))
                    discardGoogleSelection()
                    _state.update { it.copy(busy = false, message = quantityString(R.plurals.message_usb_ready, photos.size)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Scansione USB non riuscita", failure)
                _state.update {
                    it.copy(
                        busy = false,
                        message = context.getString(R.string.message_usb_unavailable),
                    )
                }
            }
        }
    }

    /**
     * Cartella scelta con il browser interno: le foto si leggono dal percorso sul disco, con il
     * permesso di lettura delle foto. Con [fromBrowser] la cartella viene prima indicizzata e, se
     * si è ancora nel browser, alla fine si torna alla schermata iniziale.
     */
    fun loadUsbFolder(path: String, fromBrowser: Boolean = false) {
        cancelPendingSources()
        // Da Android 11, senza permesso le cartelle si vedono ma le foto no: meglio dirlo che
        // segnalare una cartella vuota.
        if (!StoragePermissions.granted(context, BrowserKind.PHOTOS)) {
            showStoragePermissionDenied(BrowserKind.PHOTOS)
            return
        }
        usbJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, message = context.getString(R.string.message_usb_indexing)) }
            try {
                val directory = File(path)
                val readable = withContext(Dispatchers.IO) { directory.isDirectory && directory.canRead() }
                if (readable && fromBrowser) indexFolder(directory)
                val files = withContext(Dispatchers.IO) {
                    if (!readable) null else fileBrowser.scanPhotos(directory) { ensureActive() }
                }
                val message = when {
                    files == null -> context.getString(R.string.message_usb_unavailable)
                    files.isEmpty() -> context.getString(R.string.message_usb_empty)
                    else -> {
                        settingsRepository.saveUsbFolder(path)
                        showPhotos(files.map { it.toUsbPhotoItem() }, quantityString(R.plurals.source_usb, files.size), SourceType.USB, usbFolderTitle(path))
                        discardGoogleSelection()
                        quantityString(R.plurals.message_usb_ready, files.size)
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        message = message,
                        // Solo se nel frattempo non si è passati a un'altra schermata.
                        destination = if (it.destination == Destination.FILE_BROWSER && files?.isNotEmpty() == true) {
                            Destination.HOME
                        } else {
                            it.destination
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Lettura della cartella non riuscita", failure)
                _state.update { it.copy(busy = false, message = context.getString(R.string.message_usb_unavailable)) }
            }
        }
    }

    /**
     * Da Android 11 un'app vede i file dei dischi solo dopo che il sistema li ha indicizzati: su un
     * disco appena collegato molte foto mancherebbero. Si chiede al sistema di indicizzare subito la
     * cartella (i file già noti si saltano) e si aspetta al massimo [INDEX_TIMEOUT_MS].
     */
    private suspend fun indexFolder(directory: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        withTimeoutOrNull(INDEX_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                runCatching {
                    MediaScannerConnection.scanFile(context, arrayOf(directory.absolutePath), null) { _, _ ->
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }.onFailure { failure ->
                    Log.w(TAG, "Indicizzazione della cartella non avviata", failure)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    /** Apre il browser di cartelle interno, che non dipende dal selettore di documenti di Android. */
    fun openBrowser(kind: BrowserKind, systemPickerAvailable: Boolean) {
        browserJob?.cancel()
        _state.update {
            it.copy(
                destination = Destination.FILE_BROWSER,
                message = null,
                browser = BrowserState(
                    kind = kind,
                    loading = true,
                    systemPickerAvailable = systemPickerAvailable,
                    smbSaved = settingsRepository.loadSmbFolder()?.let { folder ->
                        BrowserServer(folder.title, folder.location.browserPath, smbAddressText(folder.location))
                    },
                ),
            )
        }
        browserJob = viewModelScope.launch {
            val roots = withContext(Dispatchers.IO) { runCatching { StorageRoots.list(context) }.getOrDefault(emptyList()) }
            _state.update { it.copy(browser = it.browser.copy(roots = roots, loading = false)) }
        }
        // Le foto si possono scegliere anche dai server multimediali della rete di casa.
        if (kind == BrowserKind.PHOTOS) browserSearchNetwork() else discoveryJob?.cancel()
    }

    /**
     * Cerca nella rete di casa i server multimediali (DLNA) e i computer con cartelle condivise
     * (SMB) che si annunciano con Bonjour; dura qualche secondo.
     */
    fun browserSearchNetwork() {
        discoveryJob?.cancel()
        _state.update { it.copy(browser = it.browser.copy(searchingNetwork = true)) }
        discoveryJob = viewModelScope.launch {
            val smbSearch = async(Dispatchers.IO) {
                runCatching { SmbDiscovery.search() }
                    .onFailure { Log.w(TAG, "Ricerca delle cartelle condivise non riuscita", it) }
                    .getOrDefault(emptyList())
            }
            val servers = withContext(Dispatchers.IO) {
                runCatching { networkMedia.discover() }
                    .onFailure { Log.w(TAG, "Ricerca dei server in rete non riuscita", it) }
                    .getOrDefault(emptyList())
            }
            val hosts = smbSearch.await()
            hosts.forEach { host -> smbNames[SmbLocation(host.address, host.port).server] = host.name }
            _state.update {
                it.copy(
                    browser = it.browser.copy(
                        searchingNetwork = false,
                        servers = servers.map { server -> BrowserServer(server.name, NetworkPath.of(server.udn, NetworkMedia.ROOT)) },
                        smbHosts = hosts.map { host ->
                            val location = SmbLocation(host.address, host.port)
                            BrowserServer(host.name, location.browserPath, smbAddressText(location))
                        },
                    ),
                )
            }
        }
    }

    fun browserOpen(path: String) {
        NetworkPath.parse(path)?.let { (udn, containerId) ->
            browserOpenNetwork(path, udn, containerId)
            return
        }
        SmbLocation.parse(path)?.let { location ->
            browserOpenSmb(location)
            return
        }
        val browser = _state.value.browser
        val root = browser.roots.firstOrNull { it.path == path } ?: browser.root
        browserJob?.cancel()
        _state.update { it.copy(browser = it.browser.copy(loading = true)) }
        browserJob = viewModelScope.launch {
            val directory = File(path)
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (!directory.isDirectory || !directory.canRead()) return@runCatching null
                    val listing = fileBrowser.list(directory, browser.kind)
                    listing to if (browser.kind == BrowserKind.PHOTOS) listing.files.size else 0
                }.getOrNull()
            }
            if (loaded == null) {
                _state.update { it.copy(browser = it.browser.copy(loading = false)) }
                showMessage(context.getString(R.string.browser_unreadable))
                return@launch
            }
            val (listing, photosHere) = loaded
            _state.update {
                it.copy(
                    browser = it.browser.copy(
                        root = root,
                        directory = directory.absolutePath,
                        folders = listing.folders.map { folder -> BrowserEntry(folder.name, folder.absolutePath) },
                        files = listing.files.map { file -> BrowserEntry(file.name, file.absolutePath) },
                        photosHere = photosHere,
                        trail = emptyList(),
                        canUse = true,
                        loading = false,
                    ),
                )
            }
        }
    }

    /**
     * Cartella di un server della rete di casa. [trail] tiene le cartelle aperte, per tornare
     * indietro: UPnP non dice qual è la cartella superiore.
     */
    private fun browserOpenNetwork(path: String, udn: String, containerId: String) {
        val browser = _state.value.browser
        val server = networkMedia.known(udn)
        val opened = browser.trail.indexOfFirst { it.path == path }
        val trail = when {
            containerId == NetworkMedia.ROOT -> listOf(BrowserEntry(server?.name ?: udn, path))
            opened >= 0 -> browser.trail.take(opened + 1)
            else -> browser.trail + BrowserEntry(browser.folders.firstOrNull { it.path == path }?.name ?: containerId, path)
        }
        browserJob?.cancel()
        _state.update { it.copy(browser = it.browser.copy(loading = true)) }
        browserJob = viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching { server?.let { networkMedia.list(it, containerId) } }
                    .onFailure { Log.w(TAG, "Cartella di rete non leggibile", it) }
                    .getOrNull()
            }
            if (page == null) {
                _state.update { it.copy(browser = it.browser.copy(loading = false)) }
                showMessage(context.getString(R.string.message_network_unavailable))
                return@launch
            }
            _state.update {
                it.copy(
                    browser = it.browser.copy(
                        root = null,
                        directory = path,
                        trail = trail,
                        folders = page.containers.map { folder -> BrowserEntry(folder.title, NetworkPath.of(udn, folder.id)) },
                        files = emptyList(),
                        photosHere = page.files.count { file -> file.mimeType.startsWith("image/") },
                        canUse = true,
                        loading = false,
                    ),
                )
            }
        }
    }

    /**
     * Cartella superiore; dalla cartella principale di un disco o di un server si torna all'elenco
     * dei dischi e dei server.
     */
    fun browserUp() {
        val browser = _state.value.browser
        val directory = browser.directory ?: return
        if (browser.trail.isNotEmpty()) {
            if (browser.trail.size > 1) browserOpen(browser.trail[browser.trail.size - 2].path) else browserShowRoots()
            return
        }
        val root = browser.root
        val parent = File(directory).parentFile
        if (root == null || File(directory).absolutePath == File(root.path).absolutePath || parent == null) {
            browserShowRoots()
        } else {
            browserOpen(parent.absolutePath)
        }
    }

    private fun browserShowRoots() {
        browserJob?.cancel()
        _state.update {
            it.copy(
                browser = it.browser.copy(
                    root = null,
                    directory = null,
                    folders = emptyList(),
                    files = emptyList(),
                    photosHere = 0,
                    trail = emptyList(),
                    canUse = true,
                    loading = false,
                ),
            )
        }
    }

    fun browserUseFolder() {
        val browser = _state.value.browser
        val path = browser.directory ?: return
        SmbLocation.parse(path)?.let { location ->
            if (location.share.isEmpty()) return
            val login = smbShares.loginFor(location) ?: SmbLogin()
            val title = browser.trail.lastOrNull()?.name ?: location.name
            loadSmbFolder(SmbFolder(location, title, login.user, login.domain), login.password, fromBrowser = true)
            return
        }
        val network = NetworkPath.parse(path)
        if (network == null) {
            loadUsbFolder(path, fromBrowser = true)
            return
        }
        val (udn, containerId) = network
        val server = networkMedia.known(udn) ?: return
        // La cartella principale prende il nome del server, le altre il proprio.
        val title = browser.trail.lastOrNull()?.name ?: server.name
        loadNetworkFolder(NetworkFolder(udn, server.location, containerId, title), fromBrowser = true)
    }

    /**
     * Foto di una cartella di un server della rete di casa, con le sottocartelle. Se il server ha
     * cambiato indirizzo lo si ritrova in rete. Con [fromBrowser], se si è ancora nel browser, alla
     * fine si torna alla schermata iniziale.
     */
    private fun loadNetworkFolder(folder: NetworkFolder, fromBrowser: Boolean = false) {
        cancelPendingSources()
        usbJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, message = context.getString(R.string.message_network_indexing)) }
            try {
                val photos = withContext(Dispatchers.IO) {
                    val server = networkMedia.find(folder.udn, folder.location) ?: return@withContext null
                    networkMedia.scanPhotos(server, folder.containerId) { ensureActive() }
                }
                val message = when {
                    photos == null -> context.getString(R.string.message_network_unavailable)
                    photos.isEmpty() -> context.getString(R.string.message_usb_empty)
                    else -> {
                        val location = networkMedia.known(folder.udn)?.location ?: folder.location
                        settingsRepository.saveNetworkFolder(folder.copy(location = location))
                        showPhotos(photos, quantityString(R.plurals.source_network, photos.size), SourceType.NETWORK, folder.title)
                        discardGoogleSelection()
                        quantityString(R.plurals.message_usb_ready, photos.size)
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        message = message,
                        destination = if (fromBrowser && it.destination == Destination.FILE_BROWSER && photos?.isNotEmpty() == true) {
                            Destination.HOME
                        } else {
                            it.destination
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Lettura della cartella di rete non riuscita", failure)
                _state.update { it.copy(busy = false, message = context.getString(R.string.message_network_unavailable)) }
            }
        }
    }

    fun browserChooseTrack(entry: BrowserEntry) {
        selectMusic(Uri.fromFile(File(entry.path)), entry.name.substringBeforeLast('.').ifBlank { entry.name })
        navigate(Destination.SETTINGS)
    }

    /** Musica di sottofondo: tutti i brani della cartella aperta, uno dopo l'altro. */
    fun browserUseMusicFolder() {
        val path = _state.value.browser.directory ?: return
        viewModelScope.launch {
            val tracks = folderTracks(path)
            if (tracks.isEmpty()) {
                showMessage(context.getString(R.string.browser_empty_music))
                return@launch
            }
            val folder = File(path)
            val name = folder.name.ifBlank { context.getString(R.string.music_default_name) }
            settingsRepository.saveMusic(Uri.fromFile(folder).toString(), name, folder = true)
            musicTracks = tracks
            _state.update { it.copy(musicUri = Uri.fromFile(folder).toString(), musicName = folderMusicName(name, tracks.size)) }
            syncMusic()
            navigate(Destination.SETTINGS)
        }
    }

    private suspend fun folderTracks(path: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { fileBrowser.list(File(path), BrowserKind.MUSIC).files.map { Uri.fromFile(it).toString() } }
            .getOrDefault(emptyList())
    }

    private fun folderMusicName(name: String, tracks: Int) = "$name · ${quantityString(R.plurals.music_folder_tracks, tracks)}"

    fun closeBrowser() {
        browserJob?.cancel()
        discoveryJob?.cancel()
        navigate(if (_state.value.browser.kind == BrowserKind.MUSIC) Destination.SETTINGS else Destination.HOME)
    }

    /** Contenuto di una cartella condivisa: [folders] null se il computer non sa elencare le condivisioni. */
    private class SmbPage(val folders: List<String>?, val photos: Int)

    /**
     * Elenco delle condivisioni di un computer, o cartella di una condivisione. Senza accesso
     * (computer mai aperto) si chiedono nome utente e password.
     */
    private fun browserOpenSmb(location: SmbLocation) {
        browserJob?.cancel()
        _state.update { it.copy(browser = it.browser.copy(loading = true)) }
        browserJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!knownSmbLogin(location)) return@runCatching null
                    if (location.share.isEmpty()) {
                        SmbPage(smbShares.shares(location), 0)
                    } else {
                        smbShares.list(location).let { listing -> SmbPage(listing.folders, listing.photos) }
                    }
                }
            }
            val page = result.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                Log.w(TAG, "Cartella condivisa non leggibile", failure)
                _state.update { it.copy(browser = it.browser.copy(loading = false)) }
                val reason = (failure as? SmbException)?.reason
                if (reason == SmbException.Reason.LOGIN) {
                    openSmbLogin(location, smbErrorRes(failure, smbShares.loginFor(location)?.guest ?: true))
                } else {
                    showMessage(context.getString(smbErrorRes(failure, guest = false)))
                }
                return@launch
            }
            when {
                page == null -> {
                    _state.update { it.copy(browser = it.browser.copy(loading = false)) }
                    openSmbLogin(location)
                }
                page.folders == null -> {
                    _state.update { it.copy(browser = it.browser.copy(loading = false)) }
                    openSmbLogin(location, askShare = true)
                }
                else -> _state.update {
                    it.copy(
                        browser = it.browser.copy(
                            root = null,
                            directory = location.browserPath,
                            trail = smbTrail(location),
                            folders = page.folders.map { name -> BrowserEntry(name, location.child(name).browserPath) },
                            files = emptyList(),
                            photosHere = page.photos,
                            canUse = location.share.isNotEmpty(),
                            loading = false,
                        ),
                    )
                }
            }
        }
    }

    /**
     * C'è un accesso per il computer: provato in questa sessione, o quello della cartella condivisa
     * salvata se è sullo stesso computer. Va chiamata fuori dal thread principale (Keystore).
     */
    private fun knownSmbLogin(location: SmbLocation): Boolean {
        if (smbShares.loginFor(location) != null) return true
        val saved = settingsRepository.loadSmbFolder()?.takeIf { it.location.server == location.server } ?: return false
        smbShares.remember(saved.location, SmbLogin(saved.user, settingsRepository.loadSmbPassword(), saved.domain))
        return true
    }

    /** Percorso aperto nel browser, dal computer alla cartella: "NAS › Foto › 2024". */
    private fun smbTrail(location: SmbLocation): List<BrowserEntry> {
        val trail = ArrayDeque<BrowserEntry>()
        var current: SmbLocation? = location
        while (current != null) {
            val name = if (current.share.isEmpty()) smbNames[current.server] ?: current.host else current.name
            trail.addFirst(BrowserEntry(name, current.browserPath))
            current = current.parent()
        }
        return trail.toList()
    }

    /** Indirizzo come lo si scrive: "nas.local", "192.168.1.20:4450" o "\\NAS\Foto\2024". */
    private fun smbAddressText(location: SmbLocation): String {
        val name = if (':' in location.host) "[${location.host}]" else location.host
        val host = if (location.port == SmbLocation.DEFAULT_PORT) name else "$name:${location.port}"
        if (location.share.isEmpty()) return host
        return "\\\\" + host + "\\" + location.share + location.path.takeIf { it.isNotEmpty() }?.let { "\\" + it.replace('/', '\\') }.orEmpty()
    }

    /** Accesso a un computer o NAS: dall'elenco dei computer trovati, o con l'indirizzo da scrivere. */
    fun openSmbLogin(location: SmbLocation? = null, @StringRes errorRes: Int? = null, askShare: Boolean = false) {
        smbJob?.cancel()
        val saved = settingsRepository.loadSmbFolder()
        val user = location?.let { smbShares.loginFor(it)?.user ?: saved?.takeIf { folder -> folder.location.server == it.server }?.user }
        _state.update {
            it.copy(
                destination = Destination.SMB_LOGIN,
                message = null,
                smb = SmbLoginState(
                    address = location?.let(::smbAddressText).orEmpty(),
                    name = location?.let { place -> smbNames[place.server] },
                    user = user.orEmpty(),
                    errorRes = errorRes,
                    askShare = askShare,
                ),
            )
        }
    }

    /**
     * "Connetti": prova l'accesso e apre le condivisioni del computer, o direttamente la condivisione
     * e la cartella scritte nell'indirizzo o nel campo della condivisione.
     */
    fun smbConnect(address: String, user: String, password: String, share: String = "") {
        val parsed = SmbAddress.parse(address)
        val server = parsed?.copy(share = "", path = "")
        val target = when {
            parsed == null || server == null -> null
            share.isNotBlank() -> SmbAddress.parse(smbAddressText(server) + "/" + share.trim().replace('\\', '/'))
            else -> parsed
        }
        if (server == null || target == null) {
            _state.update { it.copy(smb = it.smb.copy(errorRes = R.string.smb_error_address, loading = false)) }
            return
        }
        val login = SmbLogin.of(user, password)
        smbJob?.cancel()
        _state.update { it.copy(smb = it.smb.copy(loading = true, errorRes = null)) }
        smbJob = viewModelScope.launch {
            try {
                val shares = withContext(Dispatchers.IO) { smbShares.login(server, login) }
                if (target.share.isEmpty() && shares == null) {
                    _state.update { it.copy(smb = it.smb.copy(loading = false, askShare = true)) }
                    return@launch
                }
                _state.update { it.copy(destination = Destination.FILE_BROWSER, smb = it.smb.copy(loading = false)) }
                browserOpenSmb(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Accesso alla cartella condivisa non riuscito", failure)
                _state.update { it.copy(smb = it.smb.copy(loading = false, errorRes = smbErrorRes(failure, login.guest))) }
            }
        }
    }

    fun closeSmbLogin() {
        smbJob?.cancel()
        navigate(Destination.FILE_BROWSER)
    }

    @StringRes
    private fun smbErrorRes(failure: Throwable, guest: Boolean): Int = when ((failure as? SmbException)?.reason) {
        SmbException.Reason.LOGIN -> if (guest) R.string.smb_error_guest else R.string.smb_error_login
        SmbException.Reason.DENIED -> R.string.smb_error_denied
        SmbException.Reason.NOT_FOUND -> R.string.smb_error_not_found
        SmbException.Reason.NOT_LOCAL -> R.string.smb_error_not_local
        else -> R.string.smb_error_unreachable
    }

    /**
     * Foto e video di una cartella condivisa, con le sottocartelle. Con [fromBrowser], se si è ancora
     * nel browser, alla fine si torna alla schermata iniziale. La cartella e l'accesso si salvano
     * solo se la lettura riesce.
     */
    private fun loadSmbFolder(folder: SmbFolder, password: String, fromBrowser: Boolean = false) {
        cancelPendingSources()
        usbJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, message = context.getString(R.string.message_network_indexing)) }
            try {
                val photos = withContext(Dispatchers.IO) {
                    smbShares.remember(folder.location, SmbLogin(folder.user, password, folder.domain))
                    smbShares.scanPhotos(folder.location) { ensureActive() }
                }
                val message = if (photos.isEmpty()) {
                    context.getString(R.string.message_usb_empty)
                } else {
                    settingsRepository.saveSmbFolder(folder, password)
                    showPhotos(photos, quantityString(R.plurals.source_network, photos.size), SourceType.NETWORK, folder.title)
                    discardGoogleSelection()
                    _state.update {
                        it.copy(browser = it.browser.copy(smbSaved = BrowserServer(folder.title, folder.location.browserPath, smbAddressText(folder.location))))
                    }
                    quantityString(R.plurals.message_usb_ready, photos.size)
                }
                _state.update {
                    it.copy(
                        busy = false,
                        message = message,
                        destination = if (fromBrowser && it.destination == Destination.FILE_BROWSER && photos.isNotEmpty()) {
                            Destination.HOME
                        } else {
                            it.destination
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Lettura della cartella condivisa non riuscita", failure)
                _state.update { it.copy(busy = false, message = context.getString(smbErrorRes(failure, folder.user.isBlank()))) }
            }
        }
    }

    /**
     * Collegamento di un album condiviso di iCloud: la TV mostra un QR che apre sul telefono una
     * pagina dove incollare il link; in alternativa il codice si scrive con il telecomando.
     */
    fun openICloudLink() {
        icloudJob?.cancel()
        icloudPreview = null
        _state.update {
            it.copy(
                destination = Destination.ICLOUD_LINK,
                message = null,
                icloud = ICloudLinkState(savedName = it.icloud.savedName),
            )
        }
        startPairing()
        // L'album già in uso compare subito, pronto per essere ripreso.
        settingsRepository.loadICloudToken()?.let { previewICloudAlbum(it) }
    }

    private fun startPairing() {
        pairingServer?.close()
        pairingServer = null
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) {
                runCatching {
                    val address = LocalAddress.current(context) ?: return@runCatching null
                    val key = java.util.UUID.randomUUID().toString().replace("-", "")
                    lateinit var server: LinkPairingServer
                    server = LinkPairingServer(key, ICloudPairingTexts::forLanguage) { link ->
                        val token = ICloudLink.token(link)
                        // Il link arriva dal telefono: la TV apre l'album e lo mostra in anteprima, ma solo
                        // se questa pagina è ancora quella in uso e la schermata del link è aperta.
                        if (token != null) {
                            viewModelScope.launch {
                                if (pairingServer === server && _state.value.destination == Destination.ICLOUD_LINK) previewICloudAlbum(token)
                            }
                        }
                        token != null
                    }
                    val port = server.start(address)
                    pairingServer = server
                    "http://${address.hostAddress}:$port/?k=$key"
                }.onFailure { Log.w(TAG, "Pagina per il link iCloud non avviata", it) }.getOrNull()
            }
            if (_state.value.destination == Destination.ICLOUD_LINK) {
                _state.update { it.copy(icloud = it.icloud.copy(pairingUrl = url)) }
            } else {
                stopPairing()
            }
        }
    }

    private fun stopPairing() {
        val server = pairingServer
        pairingServer = null
        if (server != null) viewModelScope.launch(Dispatchers.IO) { server.close() }
    }

    /** Link o codice scritto con il telecomando, o arrivato dal telefono. */
    fun previewICloudAlbum(input: String) {
        val token = ICloudLink.token(input)
        if (token == null) {
            _state.update { it.copy(icloud = it.icloud.copy(loading = false, errorRes = R.string.icloud_error_link, token = null)) }
            return
        }
        icloudJob?.cancel()
        _state.update { it.copy(icloud = it.icloud.copy(loading = true, errorRes = null, token = null)) }
        icloudJob = viewModelScope.launch {
            try {
                val album = icloudClient.album(token)
                if (album.items.isEmpty()) {
                    _state.update { it.copy(icloud = it.icloud.copy(loading = false, errorRes = R.string.icloud_error_empty)) }
                    return@launch
                }
                icloudPreview = album
                _state.update {
                    it.copy(
                        icloud = it.icloud.copy(
                            loading = false,
                            errorRes = null,
                            token = album.token,
                            name = album.name,
                            owner = album.owner,
                            photos = album.photoCount,
                            videos = album.videoCount,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Album iCloud non aperto", failure)
                _state.update { it.copy(icloud = it.icloud.copy(loading = false, errorRes = icloudErrorRes(failure))) }
            }
        }
    }

    /** "Usa questo album": diventa la raccolta e si aggiorna da solo quando l'album cambia. */
    fun useICloudAlbum() {
        val album = icloudPreview ?: return
        cancelPendingSources()
        applyICloudAlbum(album)
        settingsRepository.saveICloudAlbum(album.token, album.name)
        discardGoogleSelection()
        stopPairing()
        _state.update {
            it.copy(
                destination = Destination.HOME,
                message = quantityString(R.plurals.message_usb_ready, album.items.size),
                icloud = it.icloud.copy(savedName = album.name),
            )
        }
    }

    fun closeICloudLink() {
        icloudJob?.cancel()
        stopPairing()
        navigate(Destination.FILE_BROWSER)
    }

    private fun applyICloudAlbum(album: ICloudAlbum) {
        icloudStream.use(album)
        showPhotos(album.toPhotos(), quantityString(R.plurals.source_icloud, album.items.size), SourceType.ICLOUD, icloudTitle(album))
        startICloudRefresh(album.token)
    }

    private fun icloudTitle(album: ICloudAlbum) = album.name.ifBlank { context.getString(R.string.home_icloud_title) }

    /** All'avvio: l'album salvato, se è ancora pubblico. */
    private fun restoreICloudAlbum(token: String) {
        usbJob = viewModelScope.launch {
            try {
                applyICloudAlbum(icloudClient.album(token))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Album iCloud non ripristinato", failure)
                showMessage(context.getString(icloudErrorRes(failure)))
            }
        }
    }

    /**
     * Le foto aggiunte all'album dagli iPhone compaiono da sole: l'elenco si rilegge ogni pochi
     * minuti finché l'album è la raccolta in uso.
     */
    private fun startICloudRefresh(token: String) {
        icloudRefreshJob?.cancel()
        icloudRefreshJob = viewModelScope.launch {
            while (true) {
                delay(ICLOUD_REFRESH_MS)
                if (baseSource != SourceType.ICLOUD) return@launch
                val album = runCatching { icloudClient.album(token) }.getOrNull() ?: continue
                if (baseSource != SourceType.ICLOUD) return@launch
                val current = icloudStream.album
                if (current != null && current.items.map { it.guid } == album.items.map { it.guid }) continue
                icloudStream.use(album)
                basePhotos = album.toPhotos()
                baseLabel = quantityString(R.plurals.source_icloud, album.items.size)
                applyCollection(restart = false)
            }
        }
    }

    private fun icloudErrorRes(failure: Throwable): Int = when ((failure as? ICloudAlbumException)?.reason) {
        ICloudAlbumException.Reason.NOT_FOUND -> R.string.icloud_error_not_found
        ICloudAlbumException.Reason.CHANGED -> R.string.icloud_error_changed
        else -> R.string.icloud_error_unreachable
    }

    fun showStoragePermissionDenied(kind: BrowserKind) = showMessage(
        context.getString(if (kind == BrowserKind.MUSIC) R.string.browser_permission_music else R.string.browser_permission_photos)
    )

    fun selectMusic(uri: Uri, displayName: String?) {
        musicRestoreJob?.cancel()
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = displayName ?: context.getString(R.string.music_default_name)
        settingsRepository.saveMusic(uri.toString(), name)
        musicTracks = listOf(uri.toString())
        _state.update { it.copy(musicUri = uri.toString(), musicName = name) }
        syncMusic()
    }

    fun removeMusic() {
        musicRestoreJob?.cancel()
        stopSilentDisco()
        musicPlayer.release()
        musicTracks = emptyList()
        settingsRepository.clearMusic()
        _state.update { it.copy(musicUri = null, musicName = null) }
    }

    /**
     * Google Foto compare se la build ha un client OAuth. Nelle build debug compare sempre, così chi
     * compila senza credenziali vede il messaggio che spiega come aggiungerle.
     */
    val showGoogle: Boolean get() = BuildConfig.DEBUG || googleAuth.isConfigured()

    fun startGoogleConnect() {
        if (!googleAuth.isConfigured()) {
            _state.update {
                it.copy(
                    destination = Destination.GOOGLE_CONNECT,
                    google = GoogleConnectState.Error(context.getString(R.string.error_google_not_configured)),
                )
            }
            return
        }
        cancelPendingSources()
        googleJob = viewModelScope.launch {
            _state.update { it.copy(destination = Destination.GOOGLE_CONNECT, google = GoogleConnectState.Idle) }
            try {
                // Una cancellazione ancora in corso cancellerebbe i nuovi token.
                disconnectJob?.join()
                val result = googleImport.run { step -> _state.update { it.copy(google = step) } }
                applyGoogleImport(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Collegamento Google non riuscito", failure)
                _state.update { it.copy(google = GoogleConnectState.Error(failure.userMessage())) }
            }
        }
    }

    /**
     * Salva la nuova selezione (un piccolo file di identificativi) e la mostra. Nessuna sospensione:
     * "Disconnetti e cancella" non può inserirsi a metà.
     */
    private fun applyGoogleImport(result: GoogleImportFlow.Result) {
        val previous = googleSelection.current()
        val selection = result.selection
        googleSelection.save(selection)
        googleStream.reset()
        settingsRepository.saveLastSource("GOOGLE")
        showPhotos(selection.toPhotos(), googleLabel(selection.items.size), SourceType.GOOGLE_PHOTOS, googleTitle())
        if (previous != null && previous.sessionId != selection.sessionId) {
            // La sessione della selezione precedente non serve più.
            viewModelScope.launch(NonCancellable) {
                googlePicker.deleteSession(::requireAccessToken, previous.sessionId)
            }
        }
        _state.update {
            it.copy(
                google = GoogleConnectState.Complete(selection.items.size, selection.expiresAtMillis),
                message = if (result.skipped > 0) {
                    quantityString(R.plurals.message_google_skipped, result.skipped)
                } else {
                    context.getString(R.string.message_google_ready)
                },
            )
        }
    }

    private suspend fun requireAccessToken(): String =
        googleAuth.validAccessToken() ?: throw UserFacingException(R.string.error_google_session_expired)

    /**
     * La selezione non è più raggiungibile (sessione scaduta o accesso revocato) ed è già stata tolta
     * dall'archivio: si torna alle foto demo e si spiega cosa fare.
     */
    private fun onGoogleSelectionGone(gone: GooglePhotosStream.Gone) {
        googleStream.reset()
        // Nel frattempo è arrivata una selezione nuova: quella persa non è più mostrata.
        if (googleSelection.current() != null) return
        if (settingsRepository.loadLastSource() == "GOOGLE") settingsRepository.saveLastSource("DEMO")
        if (basePhotos.any { it.source == SourceType.GOOGLE_PHOTOS }) {
            showDemoPhotos()
        }
        showMessage(
            context.getString(
                if (gone.reason == GooglePhotosStream.GoneReason.ACCESS_LOST) R.string.error_google_session_expired
                else R.string.error_google_selection_gone
            )
        )
    }

    /**
     * L'utente è passato a un'altra sorgente: la selezione Google non viene più usata (il pulsante
     * Google Foto ne crea sempre una nuova), quindi si cancella e si chiude la sessione presso Google.
     */
    private fun discardGoogleSelection() {
        // Senza selezione l'ultima sorgente non può restare Google.
        if (settingsRepository.loadLastSource() == "GOOGLE") settingsRepository.saveLastSource("DEMO")
        val selection = googleSelection.current() ?: return
        googleSelection.clear()
        googleStream.reset()
        viewModelScope.launch(NonCancellable) {
            googlePicker.deleteSession(::requireAccessToken, selection.sessionId)
        }
    }

    fun cancelGoogleConnect() {
        // Il riferimento resta: "Disconnetti e cancella" deve poter aspettare la fine del job annullato.
        googleJob?.cancel()
        _state.update { it.copy(google = GoogleConnectState.Idle, destination = Destination.HOME) }
    }

    fun disconnectGoogle() {
        // Solo le operazioni Google: un'eventuale scansione USB in corso non c'entra.
        restoreJob?.cancel()
        val pendingImport = googleJob
        pendingImport?.cancel()
        val previousDisconnect = disconnectJob
        // Sulla TV restano solo l'elenco delle foto e le immagini nella cache in RAM: spariscono subito.
        val selection = googleSelection.current()
        googleSelection.clear()
        googleStream.reset()
        context.imageLoader.memoryCache?.clear()
        // Chiusura della sessione Picker e revoca presso Google senza interruzioni se nel frattempo
        // l'utente esce dall'app: il client HTTP è di processo. Un nuovo collegamento aspetta la fine.
        disconnectJob = viewModelScope.launch {
            withContext(NonCancellable) {
                previousDisconnect?.join()
                // La sessione si chiude finché il token è valido; subito dopo token cancellati e revocati.
                selection?.let { googlePicker.deleteSession(::requireAccessToken, it.sessionId) }
                googleAuth.revokeAndDisconnect()
                // Un collegamento annullato o una foto in caricamento possono aver rinnovato il token
                // mentre si chiudeva tutto: si aspetta che finiscano e lo si cancella.
                pendingImport?.join()
                googleStream.drain()
                withContext(Dispatchers.IO) { googleAuth.disconnect() }
            }
        }
        val wasShowingGoogle = basePhotos.any { it.source == SourceType.GOOGLE_PHOTOS }
        if (wasShowingGoogle || settingsRepository.loadLastSource() == "GOOGLE") {
            settingsRepository.saveLastSource("DEMO")
        }
        if (wasShowingGoogle) showDemoPhotos()
        _state.update {
            it.copy(
                google = GoogleConnectState.Idle,
                message = context.getString(R.string.message_google_removed),
            )
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun showScreensaverHelp() = showMessage(context.getString(R.string.message_screensaver_unavailable))

    fun showPickerUnavailable() = showMessage(context.getString(R.string.error_picker_unavailable))

    fun startGuestParty() = guests.start()

    /** Festa ovunque: proiettore e modalità leggera, poi la festa (la rete della TV la accende l'attività). */
    fun startPartyAnywhere() {
        val settings = _state.value.settings
        if (!settings.projectorMode || !settings.lightMode) updateSettings(settings.copy(projectorMode = true, lightMode = true))
        if (!_state.value.guests.active) startGuestParty()
        navigate(Destination.GUESTS)
    }

    fun endGuestParty() {
        quietJob?.cancel()
        stopHotspot()
        usbBackupWarned = false
        showCounts.clear()
        guests.end()
        freshPhotos.clear()
        knownGuestIds = null
        _state.update { it.copy(message = context.getString(R.string.message_guests_ended)) }
    }

    fun setGuestApproval(enabled: Boolean) = guests.setApproval(enabled)

    fun setGuestQrInSlideshow(enabled: Boolean) = guests.setQrInSlideshow(enabled)

    /** Salva la rete per il QR della Wi‑Fi; restituisce false (e lo spiega) se i dati non vanno bene. */
    fun saveGuestWifi(ssid: String, password: String): Boolean {
        val problem = WifiNetwork.validate(ssid, password)
        if (problem != null) {
            showMessage(
                context.getString(
                    when (problem) {
                        WifiNetwork.Problem.MISSING_NAME -> R.string.wifi_error_name
                        WifiNetwork.Problem.NAME_TOO_LONG -> R.string.wifi_error_name_long
                        WifiNetwork.Problem.BAD_PASSWORD -> R.string.wifi_error_password
                    }
                )
            )
            return false
        }
        guests.saveWifi(WifiNetwork(ssid.trim(), password))
        navigate(wifiReturnTo)
        return true
    }

    /** Apre la Wi‑Fi degli invitati; Indietro e Salva tornano alla schermata da cui si è partiti. */
    fun openGuestWifi() {
        wifiReturnTo = _state.value.destination.takeIf { it == Destination.SETTINGS } ?: Destination.GUESTS
        navigate(Destination.GUEST_WIFI)
    }

    fun closeGuestWifi() = navigate(wifiReturnTo)

    fun setSettingsCategory(category: SettingsCategory) {
        if (_state.value.settingsCategory != category) _state.update { it.copy(settingsCategory = category) }
    }

    /**
     * "Avvia la festa" della home: accende la ricezione delle foto e fa partire lo slideshow con il QR
     * nell'angolo. Senza foto (raccolta demo) lo slideshow mostra il QR grande in attesa della prima.
     */
    fun startPartyAndPlay() {
        if (!_state.value.guests.active) guests.start()
        if (!_state.value.guests.qrInSlideshow) guests.setQrInSlideshow(true)
        _state.update { it.copy(destination = Destination.SLIDESHOW, isPlaying = true, message = null) }
        syncMusic()
    }

    fun removeGuestWifi() = guests.saveWifi(null)

    fun approveGuestPhoto(photo: PhotoItem) {
        photo.guestPhotoId?.let(guests::approve)
    }

    fun discardGuestPhoto(photo: PhotoItem) {
        photo.guestPhotoId?.let(guests::reject)
    }

    fun approveAllGuestPhotos() = guests.approveAll()

    /**
     * Aggiorna lo stato della festa e la raccolta mostrata. Le foto approvate da poco (arrivate o
     * approvate dopo la ripresa della festa) passano davanti nello slideshow; quelle riprese da una
     * festa interrotta no. Non si usa l'orologio della TV, che può cambiare all'improvviso.
     */
    private fun onGuestsChanged(party: GuestParty.State, photos: List<GuestPhotoStore.Photo>, ready: Boolean) {
        val approved = photos.filter { it.approved }
        val approvedIds = approved.mapTo(mutableSetOf()) { it.id }
        val known = knownGuestIds
        if (ready && known != null) {
            freshPhotos.add(approved.filter { it.id !in known }.map { "guest:${it.id}" })
        }
        knownGuestIds = if (ready) approvedIds else null

        val items = approved.map { it.toPhotoItem(context) }
        val approvalChanged = _state.value.guests.approval != party.approval
        _state.update {
            it.copy(
                guests = GuestsUiState(
                    active = party.active,
                    url = party.url,
                    problem = party.problem,
                    approval = party.approval,
                    qrInSlideshow = party.qrInSlideshow,
                    wifiName = (party.hotspot ?: party.wifi)?.ssid,
                    wifiPassword = (party.hotspot ?: party.wifi)?.password,
                    wifiQr = (party.hotspot ?: party.wifi)?.qrPayload(),
                    hotspot = party.hotspot != null,
                    shown = items.size,
                    pending = photos.filterNot { photo -> photo.approved }.map { photo -> photo.toPhotoItem(context) },
                ),
            )
        }
        if (approvalChanged) syncPartyFeatures()
        // Il PIN della regia cambia a ogni festa.
        if (_state.value.party.hostPin != hostRemote.pin()) refreshParty()
        if (!party.active) {
            stopSilentDisco()
            if (phoneWall.state() != null) phoneWall.stop()
        }
        if (items != guestPhotos || party.active != guestPartyActive) {
            guestPhotos = items
            guestPartyActive = party.active
            applyCollection(restart = false)
        }
        // Approvate o scartate tutte: si torna alla schermata della festa.
        if (_state.value.destination == Destination.GUEST_REVIEW && _state.value.guests.pending.isEmpty()) {
            navigate(Destination.GUESTS)
        }
    }

    /**
     * Annulla le sorgenti ancora in caricamento (scansione USB, collegamento Google, ripristino
     * della selezione): l'ultima scelta dell'utente non deve essere sovrascritta.
     */
    private fun cancelPendingSources() {
        restoreJob?.cancel()
        googleJob?.cancel()
        if (usbJob?.isActive == true) {
            usbJob?.cancel()
            _state.update { it.copy(busy = false, message = null) }
        }
    }

    /** Sostituisce la raccolta scelta ripartendo dalla prima foto dell'ordine di riproduzione. */
    private fun showPhotos(photos: List<PhotoItem>, label: String, source: SourceType, title: String) {
        basePhotos = photos
        baseLabel = label
        baseIsDemo = source == SourceType.DEMO
        baseSource = source
        baseTitle = title
        applyCollection(restart = true)
    }

    private fun showDemoPhotos() =
        showPhotos(demoPhotos, context.getString(R.string.source_demo), SourceType.DEMO, context.getString(R.string.collection_demo))

    private fun googleTitle() = context.getString(R.string.home_google_title)

    /** Nome della cartella scelta con il browser; la cartella principale di un disco ha un nome tecnico. */
    private fun usbFolderTitle(path: String): String {
        val folder = File(path)
        val volumeRoot = folder.parent == "/storage" || (folder.parentFile?.parent == "/storage" && folder.name == "0")
        return folder.name.takeUnless { volumeRoot || it.isBlank() } ?: context.getString(R.string.home_usb_title)
    }

    private fun usbTreeTitle(treeUri: Uri): String =
        runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.home_usb_title)

    /**
     * Ricalcola la raccolta mostrata. Con [restart] riparte dall'inizio; altrimenti resta sulla foto
     * corrente (arrivano o spariscono foto degli invitati mentre lo slideshow va avanti).
     */
    private fun applyCollection(restart: Boolean) {
        val current = _state.value
        val base = if (current.settings.videos) basePhotos else basePhotos.filterNot { it.video }
        // Il rullino usa e getta non ancora sviluppato tiene nascoste le foto degli invitati.
        val visibleGuests = if (partyHub.isDeveloped()) guestPhotos else emptyList()
        val photos = combineWithGuests(base, baseIsDemo, visibleGuests, guestPartyActive)
        val shuffle = current.settings.shuffle
        if (restart) {
            val index = if (shuffle && photos.isNotEmpty()) photos.indices.random() else 0
            playbackOrder.reset(photos.size, shuffle, index)
            _state.update {
                it.copy(
                    photos = photos,
                    sourceLabel = collectionLabel(),
                    collection = collectionInfo(),
                    currentIndex = index,
                    slideTick = it.slideTick + 1,
                )
            }
            return
        }
        val currentId = current.photos.getOrNull(current.currentIndex)?.id
        val index = photos.indexOfFirst { it.id == currentId }.takeIf { it >= 0 }
            ?: current.currentIndex.coerceAtMost(photos.size - 1).coerceAtLeast(0)
        // L'ordine del giro corrente resta: le foto nuove si inseriscono tra quelle non ancora viste.
        val newIndexById = photos.withIndex().associate { (position, photo) -> photo.id to position }
        playbackOrder.remap(photos.size, { old -> current.photos.getOrNull(old)?.id?.let(newIndexById::get) }, index)
        // La foto mostrata è sparita (ad esempio scartata): quella al suo posto conta come un cambio.
        val changed = photos.getOrNull(index)?.id != currentId
        _state.update {
            it.copy(
                photos = photos,
                sourceLabel = collectionLabel(),
                collection = collectionInfo(),
                currentIndex = index,
                slideTick = if (changed) it.slideTick + 1 else it.slideTick,
            )
        }
    }

    private fun collectionInfo(): CollectionInfo = when {
        // Con le foto demo e la festa in corso si mostrano solo le foto degli invitati.
        baseIsDemo && (guestPartyActive || guestPhotos.isNotEmpty()) ->
            CollectionInfo(SourceType.GUESTS, context.getString(R.string.home_guests_title), guestPhotos.size)
        else -> CollectionInfo(baseSource, baseTitle, basePhotos.size)
    }

    private fun collectionLabel(): String = when {
        guestPhotos.isEmpty() && !(baseIsDemo && guestPartyActive) -> baseLabel
        baseIsDemo -> quantityString(R.plurals.source_guests, guestPhotos.size)
        else -> context.resources.getQuantityString(R.plurals.source_with_guests, guestPhotos.size, baseLabel, guestPhotos.size)
    }

    private fun syncMusic() {
        val current = _state.value
        // Con la silent disco la musica va avanti anche in pausa, a volume zero: la sentono i telefoni.
        val volume = musicVolume()
        if (current.silentDisco || (current.isPlaying && audibleVideos.isEmpty())) {
            musicPlayer.play(musicTracks, volume)
            musicPlayer.setVolume(volume)
        } else {
            musicPlayer.pause()
        }
        syncSleepTimer()
    }

    private fun musicVolume(): Float = if (_state.value.silentDisco) 0f else _state.value.settings.musicVolume

    /** Accende o spegne la silent disco: serve la festa in corso e una musica scelta nelle impostazioni. */
    fun toggleSilentDisco() {
        if (_state.value.silentDisco) {
            stopSilentDisco()
            showMessage(context.getString(R.string.message_disco_off))
            return
        }
        if (!_state.value.guests.active) return
        if (_state.value.musicUri == null) {
            showMessage(context.getString(R.string.message_disco_no_music))
            return
        }
        _state.update { it.copy(silentDisco = true) }
        guests.discoSource = musicPlayer::nowPlaying
        syncMusic()
        showMessage(context.getString(R.string.message_disco_on))
    }

    private fun stopSilentDisco() {
        if (!_state.value.silentDisco) return
        guests.discoSource = null
        _state.update { it.copy(silentDisco = false) }
        syncMusic()
    }

    /** Un video dello slideshow inizia o smette di suonare. */
    fun setVideoAudible(id: String, audible: Boolean) {
        val changed = if (audible) audibleVideos.add(id) else audibleVideos.remove(id)
        if (changed) syncMusic()
    }

    /**
     * Pausa automatica: parte quando lo slideshow è in riproduzione e si azzera alla pausa o al
     * cambio di durata. Allo scadere mette in pausa, così lo schermo della TV può spegnersi.
     */
    private fun syncSleepTimer() {
        val current = _state.value
        val minutes = current.settings.sleepTimerMinutes
        val shouldRun = current.isPlaying && minutes > 0
        if (shouldRun && sleepJob?.isActive == true && sleepJobMinutes == minutes) return
        sleepJob?.cancel()
        sleepJob = null
        if (!shouldRun) {
            _state.update { it.copy(sleepAtMillis = null) }
            return
        }
        val durationMillis = minutes * 60_000L
        sleepJobMinutes = minutes
        _state.update { it.copy(sleepAtMillis = System.currentTimeMillis() + durationMillis) }
        sleepJob = viewModelScope.launch {
            delay(durationMillis)
            sleepJob = null
            pause()
            showMessage(context.getString(R.string.message_sleep_timer))
        }
    }

    private fun showMessage(value: String) = _state.update { it.copy(message = value) }

    private fun Throwable.userMessage(): String = when (this) {
        is UserFacingException -> context.getString(messageRes)
        is HttpStatusException -> serverMessage ?: context.getString(R.string.error_google_http, code)
        is IOException -> context.getString(R.string.error_network)
        else -> context.getString(R.string.error_generic)
    }

    private fun googleLabel(count: Int) = quantityString(R.plurals.source_google, count)

    private fun quantityString(@PluralsRes id: Int, count: Int): String =
        context.resources.getQuantityString(id, count, count)

    override fun onCleared() {
        pairingServer?.close()
        icloudRefreshJob?.cancel()
        googleJob?.cancel()
        browserJob?.cancel()
        smbJob?.cancel()
        usbJob?.cancel()
        sleepJob?.cancel()
        guests.discoSource = null
        musicPlayer.release()
        stopHotspot()
        partyHub.onChange = null
        partyPlay.onChange = null
        phoneWall.onChange = null
        partyHub.onReaction = null
        partyHub.onArrival = null
        partyHub.onDirectorCommand = null
        hostRemote.onCommand = null
        super.onCleared()
    }

    private companion object {
        const val TAG = "FotoFestaViewModel"
        /** Cartella creata sulla chiavetta per le copie delle foto della festa. */
        const val USB_BACKUP_FOLDER = "FotoFesta"
        /** Cartella delle foto dentro la capsula, citata dalla pagina con un percorso relativo. */
        const val CAPSULE_PHOTOS = "foto"
        const val INDEX_TIMEOUT_MS = 90_000L
        const val ICLOUD_REFRESH_MS = 5 * 60_000L
        /** Passaggi (meno due per ogni reazione) perché una foto sia del tutto ingiallita. */
        const val AGING_STEPS = 8
        const val REHEARSAL_MS = 120_000L
        /** Monete per chi vince una scommessa. */
        const val BET_COINS = 3
    }
}
