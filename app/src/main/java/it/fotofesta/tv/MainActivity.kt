package it.fotofesta.tv

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.cast.tv.CastReceiverContext
import it.fotofesta.tv.model.Destination
import it.fotofesta.tv.ui.FotoFestaTheme
import it.fotofesta.tv.ui.GamesScreen
import it.fotofesta.tv.ui.GoogleConnectScreen
import it.fotofesta.tv.ui.HomeScreen
import it.fotofesta.tv.ui.MemoryGameScreen
import it.fotofesta.tv.ui.MysteryGameScreen
import it.fotofesta.tv.ui.SettingsScreen
import it.fotofesta.tv.ui.SlideshowScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: FotoFestaViewModel by viewModels()
    private var mediaSession: MediaSessionCompat? = null
    private var appUiStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Se l'ultimo avvio si e chiuso per un crash, mostra un report nativo:
        // non dipende da Compose, Cast o dalle librerie multimediali.
        StartupDiagnostics.takeLastCrash(this)?.let { report ->
            StartupDiagnostics.showRecovery(this, report) { recreate() }
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // MediaSession e Cast sono accessori: errori del firmware Xiaomi o di Play Services
        // non devono impedire la visualizzazione delle foto demo.
        runCatching { setupMediaSession() }
            .onFailure { Log.w("FotoFestaMain", "MediaSession non disponibile", it) }
        appUiStarted = true
        // Lo stato della MediaSession segue il ViewModel anche quando la UI non si ricompone.
        lifecycleScope.launch {
            viewModel.state
                .map { it.isPlaying to it.photos.getOrNull(it.currentIndex)?.title }
                .distinctUntilChanged()
                .collect { (playing, title) -> updateMediaSession(playing, title) }
        }

        setContent {
            FotoFestaTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val usbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    uri?.let(viewModel::loadUsb)
                }
                val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let { viewModel.selectMusic(it, displayName(it)) }
                }

                BackHandler(enabled = state.destination != Destination.HOME, onBack = viewModel::back)

                // Lo schermo resta acceso durante slideshow e giochi, ma uno slideshow in pausa
                // (anche per la pausa automatica) lascia che la TV vada in standby.
                val keepScreenOn = when (state.destination) {
                    Destination.SLIDESHOW -> state.isPlaying
                    Destination.MEMORY, Destination.MYSTERY -> true
                    else -> false
                }
                DisposableEffect(keepScreenOn) {
                    if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose {
                        if (keepScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                LaunchedEffect(state.message) {
                    if (state.message != null) {
                        delay(5_000)
                        viewModel.clearMessage()
                    }
                }

                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when (state.destination) {
                        Destination.HOME -> HomeScreen(
                            state = state,
                            onGoogle = viewModel::startGoogleConnect,
                            onUsb = { launchPicker { usbPicker.launch(null) } },
                            onDemo = viewModel::useDemoAndPlay,
                            onPlay = viewModel::playCurrent,
                            onSettings = { viewModel.navigate(Destination.SETTINGS) },
                            onGames = { viewModel.navigate(Destination.GAMES) },
                        )
                        Destination.SETTINGS -> SettingsScreen(
                            state = state,
                            onBack = { viewModel.navigate(Destination.HOME) },
                            onChange = viewModel::updateSettings,
                            onPickMusic = { launchPicker { musicPicker.launch(arrayOf("audio/*")) } },
                            onRemoveMusic = viewModel::removeMusic,
                            onDisconnectGoogle = viewModel::disconnectGoogle,
                            onPlay = viewModel::playCurrent,
                            onOpenScreensaverSettings = ::openScreensaverSettings,
                        )
                        Destination.SLIDESHOW -> SlideshowScreen(
                            state = state,
                            upcoming = viewModel.upcomingPhoto(),
                            onBack = viewModel::back,
                            onToggle = viewModel::togglePlayback,
                            onNext = viewModel::next,
                            onPrevious = viewModel::previous,
                            onSettings = { viewModel.pause(); viewModel.navigate(Destination.SETTINGS) },
                        )
                        Destination.GAMES -> GamesScreen(
                            photoCount = state.photos.size,
                            onBack = { viewModel.navigate(Destination.HOME) },
                            onMemory = { viewModel.navigate(Destination.MEMORY) },
                            onMystery = { viewModel.navigate(Destination.MYSTERY) },
                        )
                        Destination.MEMORY -> MemoryGameScreen(state.photos) { viewModel.navigate(Destination.GAMES) }
                        Destination.MYSTERY -> MysteryGameScreen(state.photos) { viewModel.navigate(Destination.GAMES) }
                        Destination.GOOGLE_CONNECT -> GoogleConnectScreen(
                            state = state.google,
                            onCancel = viewModel::cancelGoogleConnect,
                            onRetry = viewModel::startGoogleConnect,
                            onPlay = viewModel::playCurrent,
                        )
                    }

                    // Messaggi e avanzamento in sovrimpressione, in basso: in alto coprirebbero
                    // l'intestazione con la sorgente e l'indicatore Chromecast.
                    if (state.message != null || state.busy) {
                        Column(
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .padding(bottom = 28.dp)
                                .widthIn(max = 760.dp)
                                .background(Color(0xEE17213A), RoundedCornerShape(14.dp))
                                .padding(horizontal = 22.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.message?.let { message ->
                                Text(
                                    message,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            if (state.busy) LinearProgressIndicator(Modifier.width(320.dp), color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
    }

    /** Alcune TV non hanno un selettore di documenti: senza questo controllo l'app andrebbe in crash. */
    private fun launchPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            viewModel.showPickerUnavailable()
        }
    }

    private fun openScreensaverSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            // Alcune Google TV nascondono la scelta del salvaschermo (vedi README).
            viewModel.showScreensaverHelp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        mediaSession?.let { session ->
            runCatching { session.isActive = true }
                .onFailure { Log.w("FotoFestaMain", "Attivazione MediaSession fallita", it) }
        }
    }

    override fun onStop() {
        // Con l'app in background i tasti multimediali non devono avviare musica senza interfaccia.
        mediaSession?.let { session ->
            runCatching { session.isActive = false }
                .onFailure { Log.w("FotoFestaMain", "Disattivazione MediaSession fallita", it) }
        }
        if (appUiStarted && !isChangingConfigurations) viewModel.pause()
        super.onStop()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "FotoFestaTV").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = viewModel.playCurrent()
                override fun onPause() = viewModel.pause()
                override fun onSkipToNext() = viewModel.next()
                override fun onSkipToPrevious() = viewModel.previous()
                override fun onStop() = viewModel.pause()
            })
            setSessionActivity(
                PendingIntent.getActivity(
                    this@MainActivity,
                    0,
                    Intent(this@MainActivity, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        }
        // Collega la MediaSession a Cast Connect: i comandi play/pausa/skip dei sender arrivano qui.
        runCatching {
            mediaSession?.sessionToken?.let { token ->
                CastReceiverContext.getInstance().mediaManager.setSessionCompatToken(token)
            }
        }.onFailure { Log.w("FotoFestaMain", "Collegamento Cast/MediaSession non disponibile", it) }
    }

    private fun updateMediaSession(playing: Boolean, title: String?) {
        val session = mediaSession ?: return
        runCatching {
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, getString(R.string.slideshow_session_subtitle))
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                )
                .build()
        )
        }.onFailure { Log.w("FotoFestaMain", "Aggiornamento MediaSession fallito", it) }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    override fun onDestroy() {
        mediaSession?.let { session ->
            runCatching { session.release() }
                .onFailure { Log.w("FotoFestaMain", "Rilascio MediaSession fallito", it) }
        }
        super.onDestroy()
    }
}
