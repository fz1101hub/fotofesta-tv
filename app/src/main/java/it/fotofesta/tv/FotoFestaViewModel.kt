package it.fotofesta.tv

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.fotofesta.tv.cast.CastBridge
import it.fotofesta.tv.cast.CastCommand
import it.fotofesta.tv.data.SettingsRepository
import it.fotofesta.tv.data.UsbPhotoRepository
import it.fotofesta.tv.google.GoogleDeviceAuthRepository
import it.fotofesta.tv.google.GooglePhotosPickerRepository
import it.fotofesta.tv.google.SecureTokenStore
import it.fotofesta.tv.media.BackgroundMusicPlayer
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.Destination
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.SourceType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class FotoFestaViewModel(application: Application) : AndroidViewModel(application) {
    private val context: android.content.Context
        get() = getApplication<Application>().applicationContext
    private val settingsRepository = SettingsRepository(context)
    private val usbRepository = UsbPhotoRepository(context)
    private val musicPlayer = BackgroundMusicPlayer(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val tokenStore = SecureTokenStore(context)
    private val googleAuth = GoogleDeviceAuthRepository(http, tokenStore)
    private val googlePhotos = GooglePhotosPickerRepository(context, http)
    private var googleJob: Job? = null
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
                _state.update {
                    it.copy(photos = cached, sourceLabel = "Google Foto · ${cached.size} foto in cache privata")
                }
            }
            "USB" -> settingsRepository.loadUsbTree()?.let { stored -> loadUsb(Uri.parse(stored)) }
        }
    }

    fun navigate(destination: Destination) {
        _state.update { it.copy(destination = destination, message = null) }
    }

    private var settingsOrigin = Destination.HOME

    fun openSettings() {
        settingsOrigin = _state.value.destination
        if (settingsOrigin == Destination.SLIDESHOW) pause()
        navigate(Destination.SETTINGS)
    }

    fun returnFromSettings() {
        navigate(if (settingsOrigin == Destination.SLIDESHOW) Destination.SLIDESHOW else Destination.HOME)
    }

    fun useDemoAndPlay() {
        settingsRepository.saveLastSource("DEMO")
        _state.update {
            it.copy(
                photos = demoPhotos,
                sourceLabel = "Foto dimostrative",
                destination = Destination.SLIDESHOW,
                currentIndex = 0,
                isPlaying = true,
            )
        }
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
            else {
                val nextIndex = if (current.settings.shuffle && current.photos.size > 1) {
                    generateSequence { current.photos.indices.random() }
                        .first { it != current.currentIndex }
                } else (current.currentIndex + 1) % current.photos.size
                current.copy(currentIndex = nextIndex)
            }
        }
    }

    fun previous() {
        _state.update { current ->
            if (current.photos.isEmpty()) current
            else current.copy(currentIndex = (current.currentIndex - 1 + current.photos.size) % current.photos.size)
        }
    }

    fun updateSettings(value: SlideshowSettings) {
        settingsRepository.save(value)
        _state.update { it.copy(settings = value) }
        musicPlayer.setVolume(value.musicVolume)
    }

    fun loadUsb(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "Indicizzazione cartella in corso…") }
            runCatching { usbRepository.scan(treeUri) }
                .onSuccess { photos ->
                    if (photos.isEmpty()) {
                        _state.update { it.copy(busy = false, message = "Nessuna immagine trovata nella cartella scelta.") }
                    } else {
                        settingsRepository.saveUsbTree(treeUri.toString())
                        _state.update {
                            it.copy(
                                photos = photos,
                                sourceLabel = "USB o cartella locale · ${photos.size} foto",
                                currentIndex = 0,
                                busy = false,
                                message = "${photos.size} foto pronte.",
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, message = error.userMessage()) }
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
            val requestId = googlePhotos.newRequestId()
            _state.update { it.copy(destination = Destination.GOOGLE_CONNECT, google = GoogleConnectState.Idle) }
            runCatching {
                var token = googleAuth.validAccessToken()
                if (token == null) {
                    val code = googleAuth.requestDeviceCode(requestId)
                    _state.update {
                        it.copy(
                            google = GoogleConnectState.WaitingForAuthorization(
                                code.verificationUrl,
                                code.userCode,
                                code.expiresIn,
                            )
                        )
                    }
                    token = googleAuth.pollForToken(code).accessToken
                }
                val session = googlePhotos.createSession(token, requestId)
                _state.update { it.copy(google = GoogleConnectState.WaitingForPhotos(session.pickerUri)) }
                val completed = googlePhotos.awaitSelection(token, session)
                val picked = googlePhotos.listPickedMedia(token, completed.id)
                check(picked.isNotEmpty()) { "Non hai selezionato foto." }
                val downloaded = googlePhotos.downloadToPrivateCache(token, picked) { done, total ->
                    _state.update { it.copy(google = GoogleConnectState.Downloading(done, total)) }
                }
                googlePhotos.deleteSession(token, completed.id)
                check(downloaded.isNotEmpty()) { "Non è stato possibile preparare le foto selezionate." }
                settingsRepository.saveLastSource("GOOGLE")
                _state.update {
                    it.copy(
                        photos = downloaded,
                        sourceLabel = "Google Foto · ${downloaded.size} foto in cache privata",
                        currentIndex = 0,
                        google = GoogleConnectState.Complete(downloaded.size),
                        message = "Foto Google pronte per lo slideshow.",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(google = GoogleConnectState.Error(error.userMessage())) }
            }
        }
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
        settingsRepository.saveLastSource("DEMO")
        _state.update {
            it.copy(
                photos = demoPhotos,
                sourceLabel = "Foto dimostrative",
                currentIndex = 0,
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
            is CastCommand.ChangeSettings -> updateSettings(command.settings)
            is CastCommand.Load -> {
                command.settings?.let(::updateSettings)
                _state.update {
                    it.copy(
                        photos = command.photos,
                        sourceLabel = "Chromecast · ${command.photos.size} foto",
                        destination = Destination.SLIDESHOW,
                        currentIndex = 0,
                        isPlaying = true,
                    )
                }
                syncMusic()
            }
        }
    }

    private fun syncMusic() {
        val current = _state.value
        if (current.isPlaying) musicPlayer.play(current.musicUri, current.settings.musicVolume)
        else musicPlayer.pause()
    }

    private fun showMessage(value: String) = _state.update { it.copy(message = value) }

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Operazione non riuscita. Controlla rete e impostazioni."

    override fun onCleared() {
        googleJob?.cancel()
        musicPlayer.release()
        http.dispatcher.executorService.shutdown()
        super.onCleared()
    }
}
