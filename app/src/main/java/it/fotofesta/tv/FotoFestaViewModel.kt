package it.fotofesta.tv

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.fotofesta.tv.cast.CastBridge
import it.fotofesta.tv.cast.CastCommand
import it.fotofesta.tv.data.DemoPhotos
import it.fotofesta.tv.data.SettingsRepository
import it.fotofesta.tv.data.UsbPhotoRepository
import it.fotofesta.tv.google.GoogleDeviceAuthRepository
import it.fotofesta.tv.google.GoogleImportFlow
import it.fotofesta.tv.google.GooglePhotosPickerRepository
import it.fotofesta.tv.google.HttpStatusException
import it.fotofesta.tv.google.SecureTokenStore
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
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

class FotoFestaViewModel(application: Application) : AndroidViewModel(application) {
    private val context: android.content.Context
        get() = getApplication<Application>().applicationContext
    private val settingsRepository = SettingsRepository(context)
    private val usbRepository = UsbPhotoRepository(context)
    private val musicPlayer = BackgroundMusicPlayer(context) {
        showMessage(context.getString(R.string.message_music_error))
    }
    private val googleAuth = GoogleDeviceAuthRepository(
        client = http,
        tokens = SecureTokenStore(context),
        clientId = BuildConfig.PHOTOS_CLIENT_ID,
        clientSecret = BuildConfig.PHOTOS_CLIENT_SECRET,
    )
    // noBackupFilesDir e non cacheDir: il sistema non cancella le foto importate per liberare spazio.
    private val googlePhotos = GooglePhotosPickerRepository(context.noBackupFilesDir, http)
    // Le versioni precedenti scaricavano le foto nella cache: va ripulita anche quella.
    private val legacyGooglePhotos = GooglePhotosPickerRepository(context.cacheDir, http)
    private val googleImport = GoogleImportFlow(googleAuth, googlePhotos)
    private val playbackOrder = PlaybackOrder()
    private var googleJob: Job? = null
    private var usbJob: Job? = null
    private var restoreJob: Job? = null
    private var housekeepingJob: Job? = null
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
        // Riordino dell'archivio: foto delle versioni precedenti spostate dalla cache, cartelle
        // temporanee di download interrotti eliminate. Non dipende dalla sorgente scelta, quindi
        // cancelPendingSources() non lo interrompe; importazione e cancellazione lo aspettano.
        housekeepingJob = viewModelScope.launch(Dispatchers.IO) {
            googlePhotos.adoptFrom(legacyGooglePhotos)
            googlePhotos.deleteLeftovers()
        }
        if (lastSource == "GOOGLE") {
            restoreJob = viewModelScope.launch {
                housekeepingJob?.join()
                val cached = withContext(Dispatchers.IO) { googlePhotos.cachedPhotos() }
                if (cached.isNotEmpty()) showPhotos(cached, googleLabel(cached.size))
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
                housekeepingJob?.join()
                // Le foto si mostrano appena salvate, senza aspettare la chiusura della sessione Picker:
                // un Indietro premuto in quel momento non deve scartare un archivio già sostituito.
                googleImport.run(
                    onState = { step -> _state.update { it.copy(google = step) } },
                    onImported = ::applyGoogleImport,
                )
            } catch (cancelled: CancellationException) {
                // Annullamento arrivato a scambio dell'archivio appena avvenuto: se sono mostrate foto
                // Google, l'elenco deve seguire i file presenti su disco.
                withContext(NonCancellable) { reloadShownGooglePhotos() }
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Collegamento Google non riuscito", failure)
                _state.update { it.copy(google = GoogleConnectState.Error(failure.userMessage())) }
            }
        }
    }

    private fun applyGoogleImport(result: GoogleImportFlow.Result) {
        settingsRepository.saveLastSource("GOOGLE")
        showPhotos(result.photos, googleLabel(result.photos.size))
        _state.update {
            it.copy(
                google = GoogleConnectState.Complete(result.photos.size),
                message = if (result.skipped > 0) {
                    quantityString(R.plurals.message_google_skipped, result.skipped)
                } else {
                    context.getString(R.string.message_google_ready)
                },
            )
        }
    }

    private suspend fun reloadShownGooglePhotos() {
        val shown = _state.value.photos
        if (shown.none { it.source == SourceType.GOOGLE_PHOTOS }) return
        val cached = withContext(Dispatchers.IO) { googlePhotos.cachedPhotos() }
        // Solo se nel frattempo non è cambiato nulla: un LOAD Cast o la scelta USB hanno la precedenza.
        if (cached.isNotEmpty() && _state.value.photos === shown && cached.map { it.id } != shown.map { it.id }) {
            showPhotos(cached, googleLabel(cached.size))
        }
    }

    fun cancelGoogleConnect() {
        googleJob?.cancel()
        googleJob = null
        _state.update { it.copy(google = GoogleConnectState.Idle, destination = Destination.HOME) }
    }

    fun disconnectGoogle() {
        // Solo le operazioni Google: un'eventuale scansione USB in corso non c'entra.
        restoreJob?.cancel()
        val pendingImport = googleJob
        pendingImport?.cancel()
        // Revoca e cancellazione dei file (anche migliaia di foto 4K) fuori dal thread principale e
        // senza interruzioni se nel frattempo l'utente esce dall'app: il client HTTP è di processo.
        viewModelScope.launch {
            withContext(NonCancellable) {
                // I token locali spariscono per primi, poi la revoca presso Google e, in parallelo, i file.
                val files = launch(Dispatchers.IO) {
                    housekeepingJob?.join()
                    googlePhotos.clearCache()
                    legacyGooglePhotos.clearCache()
                    // Un download annullato a un passo dalla fine può aver appena salvato l'archivio.
                    pendingImport?.join()
                    googlePhotos.clearCache()
                }
                googleAuth.revokeAndDisconnect()
                // Un'importazione annullata può aver rinnovato il token mentre si chiudeva: va cancellato.
                pendingImport?.join()
                withContext(Dispatchers.IO) { googleAuth.disconnect() }
                files.join()
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

        // Unico per tutto il processo e mai chiuso: la revoca di "Disconnetti e cancella" deve poter
        // terminare anche dopo la chiusura dell'Activity, e una nuova Activity riusa connessioni e thread.
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
