package it.fotofesta.tv

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import it.fotofesta.tv.cast.CastBridge
import it.fotofesta.tv.cast.CastCommand
import it.fotofesta.tv.data.DemoPhotos
import it.fotofesta.tv.data.SettingsRepository
import it.fotofesta.tv.data.UsbPhotoRepository
import it.fotofesta.tv.google.GoogleImportFlow
import it.fotofesta.tv.google.HttpStatusException
import it.fotofesta.tv.media.BackgroundMusicPlayer
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.Destination
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PlaybackOrder
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.SourceType
import it.fotofesta.tv.model.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class FotoFestaViewModel(application: Application) : AndroidViewModel(application) {
    private val context: android.content.Context
        get() = getApplication<Application>().applicationContext
    private val settingsRepository = SettingsRepository(context)
    private val usbRepository = UsbPhotoRepository(context)
    private val musicPlayer = BackgroundMusicPlayer(context) {
        showMessage(context.getString(R.string.message_music_error))
    }
    private val services = context.appServices
    private val googleAuth = services.googleAuth
    private val googlePicker = services.googlePicker
    private val googleSelection = services.googleSelection
    private val googleStream = services.googleStream
    private val googleImport = GoogleImportFlow(googleAuth, googlePicker)
    private val playbackOrder = PlaybackOrder()
    private var googleJob: Job? = null
    private var usbJob: Job? = null
    private var restoreJob: Job? = null
    private var disconnectJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepJobMinutes = 0
    private val savedMusic = settingsRepository.loadMusic()

    private val demoPhotos = DemoPhotos.load(context)

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
        // Prima si ripristina l'ultima sorgente, poi si ascoltano i comandi Cast: un LOAD già in coda
        // (avvio tramite Cast Connect) annulla il ripristino invece di essere sovrascritto.
        val lastSource = settingsRepository.loadLastSource()
        if (lastSource == "GOOGLE") {
            restoreJob = viewModelScope.launch {
                val selection = withContext(Dispatchers.IO) { googleSelection.current() }
                when {
                    // Selezione delle versioni che scaricavano le foto: non c'è più nulla da mostrare.
                    selection == null -> settingsRepository.saveLastSource("DEMO")
                    selection.isExpired(System.currentTimeMillis()) -> onGoogleSelectionGone()
                    else -> showPhotos(selection.toPhotos(), googleLabel(selection.items.size))
                }
            }
        }
        if (lastSource == "USB") settingsRepository.loadUsbTree()?.let { stored -> loadUsb(Uri.parse(stored)) }
        viewModelScope.launch {
            CastBridge.connected.collect { connected ->
                _state.update { it.copy(castSenderConnected = connected) }
            }
        }
        viewModelScope.launch {
            CastBridge.commands.collect(::handleCastCommand)
        }
        viewModelScope.launch {
            googleStream.selectionGone.collect { gone -> if (gone) onGoogleSelectionGone() }
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
            Destination.SETTINGS, Destination.GAMES -> navigate(Destination.HOME)
        }
    }

    fun useDemoAndPlay() {
        cancelPendingSources()
        settingsRepository.saveLastSource("DEMO")
        showPhotos(demoPhotos, context.getString(R.string.source_demo))
        _state.update { it.copy(destination = Destination.SLIDESHOW, isPlaying = true) }
        syncMusic()
    }

    fun playCurrent() {
        if (_state.value.photos.isEmpty()) {
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
    fun next() {
        if (_state.value.photos.isEmpty()) return
        val index = playbackOrder.next(_state.value.currentIndex)
        _state.update { it.copy(currentIndex = index) }
    }

    fun previous() {
        if (_state.value.photos.isEmpty()) return
        val index = playbackOrder.previous(_state.value.currentIndex)
        _state.update { it.copy(currentIndex = index) }
    }

    /** Foto successiva prevista, usata dallo slideshow per precaricarla. */
    fun upcomingPhoto(): PhotoItem? {
        val current = _state.value
        return playbackOrder.peekNext(current.currentIndex)?.let(current.photos::getOrNull)
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
                    showPhotos(photos, quantityString(R.plurals.source_usb, photos.size))
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
        showPhotos(selection.toPhotos(), googleLabel(selection.items.size))
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

    /** La sessione Picker è scaduta o non è più accessibile: le foto vanno scelte di nuovo. */
    private fun onGoogleSelectionGone() {
        googleSelection.clear()
        googleStream.reset()
        if (settingsRepository.loadLastSource() == "GOOGLE") settingsRepository.saveLastSource("DEMO")
        if (_state.value.photos.any { it.source == SourceType.GOOGLE_PHOTOS }) {
            showPhotos(demoPhotos, context.getString(R.string.source_demo))
        }
        showMessage(context.getString(R.string.error_google_selection_gone))
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
                // Un collegamento annullato può aver rinnovato il token mentre si chiudeva: va cancellato.
                pendingImport?.join()
                withContext(Dispatchers.IO) { googleAuth.disconnect() }
            }
        }
        val wasShowingGoogle = _state.value.photos.any { it.source == SourceType.GOOGLE_PHOTOS }
        if (wasShowingGoogle || settingsRepository.loadLastSource() == "GOOGLE") {
            settingsRepository.saveLastSource("DEMO")
        }
        if (wasShowingGoogle) showPhotos(demoPhotos, context.getString(R.string.source_demo))
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

    private fun handleCastCommand(command: CastCommand) {
        when (command) {
            CastCommand.Play -> playCurrent()
            CastCommand.Pause -> pause()
            CastCommand.Next -> next()
            CastCommand.Previous -> previous()
            is CastCommand.ChangeSettings -> updateSettings(command.settings.applyTo(_state.value.settings))
            is CastCommand.Load -> {
                cancelPendingSources()
                _state.update { it.copy(google = GoogleConnectState.Idle) }
                command.settings?.let { updateSettings(it.applyTo(_state.value.settings)) }
                showPhotos(command.photos, quantityString(R.plurals.source_cast, command.photos.size))
                _state.update { it.copy(destination = Destination.SLIDESHOW, isPlaying = true) }
                syncMusic()
            }
        }
    }

    /**
     * Annulla le sorgenti ancora in caricamento (scansione USB, collegamento Google, ripristino
     * della cache): l'ultima scelta dell'utente o del sender Cast non deve essere sovrascritta.
     */
    private fun cancelPendingSources() {
        restoreJob?.cancel()
        googleJob?.cancel()
        if (usbJob?.isActive == true) {
            usbJob?.cancel()
            _state.update { it.copy(busy = false, message = null) }
        }
    }

    /** Sostituisce la raccolta attiva ripartendo dalla prima foto dell'ordine di riproduzione. */
    private fun showPhotos(photos: List<PhotoItem>, label: String) {
        val shuffle = _state.value.settings.shuffle
        val start = if (shuffle && photos.isNotEmpty()) photos.indices.random() else 0
        playbackOrder.reset(photos.size, shuffle, start)
        _state.update { it.copy(photos = photos, sourceLabel = label, currentIndex = start) }
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
        usbJob?.cancel()
        sleepJob?.cancel()
        musicPlayer.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "FotoFestaViewModel"
    }
}
