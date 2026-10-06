package it.fotofesta.tv

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.fotofesta.tv.cast.CastBridge
import it.fotofesta.tv.cast.CastCommand
import it.fotofesta.tv.data.SettingsRepository
import it.fotofesta.tv.data.UsbPhotoRepository
import it.fotofesta.tv.google.GoogleDeviceAuthRepository
import it.fotofesta.tv.google.GooglePhotosPickerRepository
import it.fotofesta.tv.google.HttpStatusException
import it.fotofesta.tv.google.SecureTokenStore
import it.fotofesta.tv.media.BackgroundMusicPlayer
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.Destination
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.PlaybackOrder
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class FotoFestaViewModel(application: Application) : AndroidViewModel(application) {
    private val context: android.content.Context
        get() = getApplication<Application>().applicationContext
    private val settingsRepository = SettingsRepository(context)
    private val usbRepository = UsbPhotoRepository(context)
    private val musicPlayer = BackgroundMusicPlayer(context) {
        showMessage("Impossibile riprodurre il brano scelto. Selezionane un altro dalle impostazioni.")
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val tokenStore = SecureTokenStore(context)
    private val googleAuth = GoogleDeviceAuthRepository(http, tokenStore)
    private val googlePhotos = GooglePhotosPickerRepository(context, http)
    private val playbackOrder = PlaybackOrder()
    private var googleJob: Job? = null
    private var usbJob: Job? = null
    private val savedMusic = settingsRepository.loadMusic()

    private val demoPhotos = listOf(
        PhotoItem("demo:coast", "Costa al tramonto", PhotoRef.Resource(R.drawable.demo_coast), SourceType.DEMO),
        PhotoItem("demo:picnic", "Picnic in famiglia", PhotoRef.Resource(R.drawable.demo_picnic), SourceType.DEMO),
        PhotoItem("demo:mountains", "Lago nelle Dolomiti", PhotoRef.Resource(R.drawable.demo_mountains), SourceType.DEMO),
        PhotoItem("demo:birthday", "Festa di compleanno", PhotoRef.Resource(R.drawable.demo_birthday), SourceType.DEMO),
    )

    private val _state = MutableStateFlow(
        AppState(
            photos = demoPhotos,
            settings = settingsRepository.load(),
            musicUri = savedMusic?.uri,
            musicName = savedMusic?.name,
        )
    )
    val state = _state.asStateFlow()

    init {
        playbackOrder.reset(demoPhotos.size, _state.value.settings.shuffle)
        viewModelScope.launch {
            CastBridge.connected.collect { connected ->
                _state.update { it.copy(castSenderConnected = connected) }
            }
        }
        viewModelScope.launch {
            CastBridge.commands.collect(::handleCastCommand)
        }
        when (settingsRepository.loadLastSource()) {
            "GOOGLE" -> googlePhotos.cachedPhotos().takeIf { it.isNotEmpty() }?.let { cached ->
                showPhotos(cached, googleLabel(cached.size))
            }
            "USB" -> settingsRepository.loadUsbTree()?.let { stored -> loadUsb(Uri.parse(stored)) }
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
        settingsRepository.saveLastSource("DEMO")
        showPhotos(demoPhotos, DEMO_LABEL)
        _state.update { it.copy(destination = Destination.SLIDESHOW, isPlaying = true) }
        syncMusic()
    }

    fun playCurrent() {
        if (_state.value.photos.isEmpty()) {
            showMessage("Scegli prima una sorgente di foto.")
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

    fun next() {
        _state.update { current ->
            if (current.photos.isEmpty()) current
            else current.copy(currentIndex = playbackOrder.next(current.currentIndex))
        }
    }

    fun previous() {
        _state.update { current ->
            if (current.photos.isEmpty()) current
            else current.copy(currentIndex = playbackOrder.previous(current.currentIndex))
        }
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
    }

    fun loadUsb(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        usbJob?.cancel()
        usbJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "Indicizzazione cartella in corso…") }
            try {
                val photos = usbRepository.scan(treeUri)
                if (photos.isEmpty()) {
                    _state.update { it.copy(busy = false, message = "Nessuna immagine trovata nella cartella scelta.") }
                } else {
                    settingsRepository.saveUsbTree(treeUri.toString())
                    showPhotos(photos, "USB o cartella locale · ${photos.size} foto")
                    _state.update { it.copy(busy = false, message = "${photos.size} foto pronte.") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Scansione USB non riuscita", failure)
                _state.update {
                    it.copy(
                        busy = false,
                        message = "Cartella non disponibile: collega di nuovo il disco USB o scegli un'altra cartella.",
                    )
                }
            }
        }
    }

    fun selectMusic(uri: Uri, displayName: String?) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = displayName ?: "Brano scelto"
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
                    google = GoogleConnectState.Error(
                        "Mancano le credenziali Google Photos Picker. Segui CONFIGURATION.md e ricompila l'app."
                    ),
                )
            }
            return
        }
        googleJob?.cancel()
        googleJob = viewModelScope.launch {
            _state.update { it.copy(destination = Destination.GOOGLE_CONNECT, google = GoogleConnectState.Idle) }
            var token: String? = null
            var sessionId: String? = null
            try {
                var accessToken = authorizeGoogle()
                val session = try {
                    googlePhotos.createSession(accessToken)
                } catch (unauthorized: HttpStatusException) {
                    if (unauthorized.code != 401) throw unauthorized
                    // Token revocato dall'utente: si ripete il collegamento da capo una sola volta.
                    googleAuth.disconnect()
                    accessToken = authorizeGoogle()
                    googlePhotos.createSession(accessToken)
                }
                token = accessToken
                sessionId = session.id
                _state.update { it.copy(google = GoogleConnectState.WaitingForPhotos(session.pickerUri)) }
                val completed = googlePhotos.awaitSelection(accessToken, session)
                val picked = googlePhotos.listPickedMedia(accessToken, completed.id)
                check(picked.isNotEmpty()) { "Non hai selezionato foto." }
                _state.update { it.copy(google = GoogleConnectState.Downloading(0, picked.size)) }
                val downloaded = googlePhotos.downloadToPrivateCache(accessToken, picked) { done, total ->
                    _state.update { it.copy(google = GoogleConnectState.Downloading(done, total)) }
                }
                check(downloaded.isNotEmpty()) { "Non è stato possibile preparare le foto selezionate." }
                settingsRepository.saveLastSource("GOOGLE")
                showPhotos(downloaded, googleLabel(downloaded.size))
                val skipped = picked.size - downloaded.size
                _state.update {
                    it.copy(
                        google = GoogleConnectState.Complete(downloaded.size),
                        message = if (skipped > 0) {
                            "$skipped foto non scaricate (spazio insufficiente o file non disponibili)."
                        } else {
                            "Foto Google pronte per lo slideshow."
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(google = GoogleConnectState.Error(error.userMessage())) }
            } finally {
                val usedToken = token
                val openSession = sessionId
                if (usedToken != null && openSession != null) {
                    withContext(NonCancellable) { googlePhotos.deleteSession(usedToken, openSession) }
                }
            }
        }
    }

    /** Restituisce un token valido o avvia il flusso di autorizzazione con codice e QR. */
    private suspend fun authorizeGoogle(): String {
        googleAuth.validAccessToken()?.let { return it }
        val code = googleAuth.requestDeviceCode()
        _state.update {
            it.copy(
                google = GoogleConnectState.WaitingForAuthorization(
                    code.verificationUrl,
                    code.userCode,
                    code.expiresIn,
                )
            )
        }
        return googleAuth.pollForToken(code).accessToken
    }

    fun cancelGoogleConnect() {
        googleJob?.cancel()
        googleJob = null
        _state.update { it.copy(google = GoogleConnectState.Idle, destination = Destination.HOME) }
    }

    fun disconnectGoogle() {
        googleJob?.cancel()
        googleAuth.disconnect()
        googlePhotos.clearCache()
        val wasShowingGoogle = _state.value.photos.any { it.source == SourceType.GOOGLE_PHOTOS }
        if (wasShowingGoogle || settingsRepository.loadLastSource() == "GOOGLE") {
            settingsRepository.saveLastSource("DEMO")
        }
        if (wasShowingGoogle) showPhotos(demoPhotos, DEMO_LABEL)
        _state.update {
            it.copy(
                google = GoogleConnectState.Idle,
                message = "Account e cache Google Foto rimossi da questa TV.",
            )
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun handleCastCommand(command: CastCommand) {
        when (command) {
            CastCommand.Play -> playCurrent()
            CastCommand.Pause -> pause()
            CastCommand.Next -> next()
            CastCommand.Previous -> previous()
            is CastCommand.ChangeSettings -> updateSettings(command.settings.applyTo(_state.value.settings))
            is CastCommand.Load -> {
                command.settings?.let { updateSettings(it.applyTo(_state.value.settings)) }
                showPhotos(command.photos, "Chromecast · ${command.photos.size} foto")
                _state.update { it.copy(destination = Destination.SLIDESHOW, isPlaying = true) }
                syncMusic()
            }
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
    }

    private fun showMessage(value: String) = _state.update { it.copy(message = value) }

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Operazione non riuscita. Controlla rete e impostazioni."

    private fun googleLabel(count: Int) = "Google Foto · $count foto in cache privata"

    override fun onCleared() {
        googleJob?.cancel()
        usbJob?.cancel()
        musicPlayer.release()
        http.dispatcher.executorService.shutdown()
        super.onCleared()
    }

    private companion object {
        const val TAG = "FotoFestaViewModel"
        const val DEMO_LABEL = "Foto dimostrative"
    }
}
