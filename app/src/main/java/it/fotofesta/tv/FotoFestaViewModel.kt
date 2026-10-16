package it.fotofesta.tv

import android.app.Application
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.PluralsRes
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
import it.fotofesta.tv.google.GooglePhotosStream
import it.fotofesta.tv.google.HttpStatusException
import it.fotofesta.tv.guests.GuestParty
import it.fotofesta.tv.guests.GuestPhotoStore
import it.fotofesta.tv.guests.WifiNetwork
import it.fotofesta.tv.guests.guestPhotoId
import it.fotofesta.tv.guests.toPhotoItem
import it.fotofesta.tv.media.BackgroundMusicPlayer
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.BrowserEntry
import it.fotofesta.tv.model.BrowserKind
import it.fotofesta.tv.model.BrowserState
import it.fotofesta.tv.model.Destination
import it.fotofesta.tv.model.FreshPhotos
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.model.GuestsUiState
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PlaybackOrder
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.SourceType
import it.fotofesta.tv.model.UserFacingException
import it.fotofesta.tv.model.combineWithGuests
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val musicPlayer = BackgroundMusicPlayer(context) {
        showMessage(context.getString(R.string.message_music_error))
    }
    private val services = context.appServices
    private val googleAuth = services.googleAuth
    private val googlePicker = services.googlePicker
    private val googleSelection = services.googleSelection
    private val googleStream = services.googleStream
    private val googleImport = GoogleImportFlow(googleAuth, googlePicker)
    private val guests = services.guests
    private val playbackOrder = PlaybackOrder()
    private val freshPhotos = FreshPhotos()
    private var googleJob: Job? = null
    private var usbJob: Job? = null
    private var restoreJob: Job? = null
    private var disconnectJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepJobMinutes = 0
    private val savedMusic = settingsRepository.loadMusic()

    private val demoPhotos = DemoPhotos.load(context)

    // Raccolta scelta (demo, USB o Google) e foto approvate degli invitati: insieme formano quella mostrata.
    private var basePhotos: List<PhotoItem> = demoPhotos
    private var baseLabel: String = context.getString(R.string.source_demo)
    private var baseIsDemo = true
    private var guestPhotos: List<PhotoItem> = emptyList()
    private var guestPartyActive = false
    /** Foto approvate già viste; null finché le foto di una festa interrotta non sono state riprese. */
    private var knownGuestIds: Set<String>? = null

    private val _state = MutableStateFlow(
        AppState(
            photos = demoPhotos,
            sourceLabel = context.getString(R.string.source_demo),
            settings = settingsRepository.load(),
            musicUri = savedMusic?.uri,
            musicName = savedMusic?.let { saved -> saved.name.ifBlank { context.getString(R.string.music_default_name) } },
        )
    )
    val state = _state.asStateFlow()

    init {
        playbackOrder.reset(demoPhotos.size, _state.value.settings.shuffle)
        val lastSource = settingsRepository.loadLastSource()
        if (lastSource == "GOOGLE") {
            restoreJob = viewModelScope.launch {
                val selection = withContext(Dispatchers.IO) { googleSelection.current() }
                when {
                    selection != null -> showPhotos(selection.toPhotos(), googleLabel(selection.items.size), isDemo = false)
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
        viewModelScope.launch {
            googleStream.gone.collect { gone -> if (gone != null) onGoogleSelectionGone(gone) }
        }
        viewModelScope.launch {
            combine(guests.state, guests.store.photos, guests.ready) { party, photos, ready -> Triple(party, photos, ready) }
                .collect { (party, photos, ready) -> onGuestsChanged(party, photos, ready) }
        }
    }

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
            Destination.MEMORY, Destination.MYSTERY -> navigate(Destination.GAMES)
            Destination.GOOGLE_CONNECT -> cancelGoogleConnect()
            Destination.SETTINGS, Destination.GAMES, Destination.GUESTS -> navigate(Destination.HOME)
            Destination.GUEST_REVIEW, Destination.GUEST_WIFI -> navigate(Destination.GUESTS)
            Destination.FILE_BROWSER -> if (_state.value.browser.directory != null) browserUp() else closeBrowser()
        }
    }

    fun useDemoAndPlay() {
        cancelPendingSources()
        settingsRepository.saveLastSource("DEMO")
        showPhotos(demoPhotos, context.getString(R.string.source_demo), isDemo = true)
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
        musicPlayer.setVolume(value.musicVolume)
        if (previous.sleepTimerMinutes != value.sleepTimerMinutes) syncSleepTimer()
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
                    showPhotos(photos, quantityString(R.plurals.source_usb, photos.size), isDemo = false)
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
                        showPhotos(files.map { it.toUsbPhotoItem() }, quantityString(R.plurals.source_usb, files.size), isDemo = false)
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
                browser = BrowserState(kind = kind, loading = true, systemPickerAvailable = systemPickerAvailable),
            )
        }
        browserJob = viewModelScope.launch {
            val roots = withContext(Dispatchers.IO) { runCatching { StorageRoots.list(context) }.getOrDefault(emptyList()) }
            _state.update { it.copy(browser = it.browser.copy(roots = roots, loading = false)) }
            if (kind == BrowserKind.PHOTOS && roots.none { it.removable }) showMessage(context.getString(R.string.browser_no_drive))
        }
    }

    fun browserOpen(path: String) {
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
                        loading = false,
                    ),
                )
            }
        }
    }

    /** Cartella superiore; dalla cartella principale di un disco si torna all'elenco dei dischi. */
    fun browserUp() {
        val browser = _state.value.browser
        val directory = browser.directory ?: return
        val root = browser.root
        val parent = File(directory).parentFile
        if (root == null || File(directory).absolutePath == File(root.path).absolutePath || parent == null) {
            browserJob?.cancel()
            _state.update {
                it.copy(
                    browser = it.browser.copy(
                        root = null,
                        directory = null,
                        folders = emptyList(),
                        files = emptyList(),
                        photosHere = 0,
                        loading = false,
                    ),
                )
            }
        } else {
            browserOpen(parent.absolutePath)
        }
    }

    fun browserUseFolder() {
        val path = _state.value.browser.directory ?: return
        loadUsbFolder(path, fromBrowser = true)
    }

    fun browserChooseTrack(entry: BrowserEntry) {
        selectMusic(Uri.fromFile(File(entry.path)), entry.name.substringBeforeLast('.').ifBlank { entry.name })
        navigate(Destination.SETTINGS)
    }

    fun closeBrowser() {
        browserJob?.cancel()
        navigate(if (_state.value.browser.kind == BrowserKind.MUSIC) Destination.SETTINGS else Destination.HOME)
    }

    fun showStoragePermissionDenied(kind: BrowserKind) = showMessage(
        context.getString(if (kind == BrowserKind.MUSIC) R.string.browser_permission_music else R.string.browser_permission_photos)
    )

    fun selectMusic(uri: Uri, displayName: String?) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = displayName ?: context.getString(R.string.music_default_name)
        settingsRepository.saveMusic(uri.toString(), name)
        _state.update { it.copy(musicUri = uri.toString(), musicName = name) }
        syncMusic()
    }

    fun removeMusic() {
        musicPlayer.release()
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
        showPhotos(selection.toPhotos(), googleLabel(selection.items.size), isDemo = false)
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
            showPhotos(demoPhotos, context.getString(R.string.source_demo), isDemo = true)
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
        if (wasShowingGoogle) showPhotos(demoPhotos, context.getString(R.string.source_demo), isDemo = true)
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

    fun endGuestParty() {
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
        navigate(Destination.GUESTS)
        return true
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
        _state.update {
            it.copy(
                guests = GuestsUiState(
                    active = party.active,
                    url = party.url,
                    problem = party.problem,
                    approval = party.approval,
                    qrInSlideshow = party.qrInSlideshow,
                    wifiName = party.wifi?.ssid,
                    wifiPassword = party.wifi?.password,
                    wifiQr = party.wifi?.qrPayload(),
                    shown = items.size,
                    pending = photos.filterNot { photo -> photo.approved }.map { photo -> photo.toPhotoItem(context) },
                ),
            )
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
    private fun showPhotos(photos: List<PhotoItem>, label: String, isDemo: Boolean) {
        basePhotos = photos
        baseLabel = label
        baseIsDemo = isDemo
        applyCollection(restart = true)
    }

    /**
     * Ricalcola la raccolta mostrata. Con [restart] riparte dall'inizio; altrimenti resta sulla foto
     * corrente (arrivano o spariscono foto degli invitati mentre lo slideshow va avanti).
     */
    private fun applyCollection(restart: Boolean) {
        val current = _state.value
        val photos = combineWithGuests(basePhotos, baseIsDemo, guestPhotos, guestPartyActive)
        val shuffle = current.settings.shuffle
        if (restart) {
            val index = if (shuffle && photos.isNotEmpty()) photos.indices.random() else 0
            playbackOrder.reset(photos.size, shuffle, index)
            _state.update {
                it.copy(photos = photos, sourceLabel = collectionLabel(), currentIndex = index, slideTick = it.slideTick + 1)
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
                currentIndex = index,
                slideTick = if (changed) it.slideTick + 1 else it.slideTick,
            )
        }
    }

    private fun collectionLabel(): String = when {
        guestPhotos.isEmpty() && !(baseIsDemo && guestPartyActive) -> baseLabel
        baseIsDemo -> quantityString(R.plurals.source_guests, guestPhotos.size)
        else -> context.resources.getQuantityString(R.plurals.source_with_guests, guestPhotos.size, baseLabel, guestPhotos.size)
    }

    private fun syncMusic() {
        val current = _state.value
        if (current.isPlaying) musicPlayer.play(current.musicUri, current.settings.musicVolume)
        else musicPlayer.pause()
        syncSleepTimer()
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
        googleJob?.cancel()
        browserJob?.cancel()
        usbJob?.cancel()
        sleepJob?.cancel()
        musicPlayer.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "FotoFestaViewModel"
        const val INDEX_TIMEOUT_MS = 90_000L
    }
}
