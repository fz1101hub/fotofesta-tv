package it.fotofesta.tv

import android.app.PendingIntent
import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import it.fotofesta.tv.model.Destination
import it.fotofesta.tv.ui.GraphiteSurface
import it.fotofesta.tv.ui.FotoFestaTheme
import it.fotofesta.tv.ui.GamesScreen
import it.fotofesta.tv.ui.GoogleConnectScreen
import it.fotofesta.tv.ui.HomeScreen
import it.fotofesta.tv.ui.MemoryGameScreen
import it.fotofesta.tv.ui.MysteryGameScreen
import it.fotofesta.tv.ui.SettingsScreen
import it.fotofesta.tv.ui.SourcesScreen
import it.fotofesta.tv.ui.SlideshowScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: FotoFestaViewModel by viewModels()
    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setupMediaSession()

        setContent {
            FotoFestaTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val usbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    uri?.let(viewModel::loadUsb)
                }
                val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let {
                        runCatching {
                            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        viewModel.selectMusic(it, displayName(it))
                    }
                }

                BackHandler(enabled = state.destination != Destination.HOME) {
                    if (state.destination == Destination.SLIDESHOW) viewModel.pause()
                    if (state.destination == Destination.SETTINGS) {
                        viewModel.returnFromSettings()
                    } else viewModel.navigate(
                        if (state.destination in setOf(Destination.MEMORY, Destination.MYSTERY)) Destination.GAMES
                        else Destination.HOME
                    )
                }

                DisposableEffect(state.destination) {
                    if (state.destination in setOf(Destination.SLIDESHOW, Destination.MEMORY, Destination.MYSTERY)) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose { }
                }

                LaunchedEffect(state.isPlaying, state.currentIndex, state.photos) {
                    updateMediaSession(state.isPlaying, state.currentIndex, state.photos.getOrNull(state.currentIndex)?.title)
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
                            onUsb = { usbPicker.launch(null) },
                            onDemo = viewModel::useDemoAndPlay,
                            onPlay = viewModel::playCurrent,
                            onSources = { viewModel.navigate(Destination.SOURCES) },
                            onSettings = viewModel::openSettings,
                            onGames = { viewModel.navigate(Destination.GAMES) },
                        )
                        Destination.SOURCES -> SourcesScreen(
                            state = state,
                            onBack = { viewModel.navigate(Destination.HOME) },
                            onGoogle = viewModel::startGoogleConnect,
                            onUsb = { usbPicker.launch(null) },
                            onDemo = viewModel::useDemoAndPlay,
                        )
                        Destination.SETTINGS -> SettingsScreen(
                            state = state,
                            onBack = viewModel::returnFromSettings,
                            onChange = viewModel::updateSettings,
                            onPickMusic = { musicPicker.launch(arrayOf("audio/*")) },
                            onRemoveMusic = viewModel::removeMusic,
                            onDisconnectGoogle = viewModel::disconnectGoogle,
                            onPlay = viewModel::playCurrent,
                        )
                        Destination.SLIDESHOW -> SlideshowScreen(
                            state = state,
                            onBack = { viewModel.pause(); viewModel.navigate(Destination.HOME) },
                            onToggle = viewModel::togglePlayback,
                            onNext = viewModel::next,
                            onPrevious = viewModel::previous,
                            onSettings = viewModel::openSettings,
                        )
                        Destination.GAMES -> GamesScreen(
                            photos = state.photos,
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

                    state.message?.takeIf { state.destination != Destination.HOME }?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.align(Alignment.TopCenter)
                                .padding(top = 20.dp)
                                .background(GraphiteSurface.copy(alpha = .95f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 22.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStop() {
        if (!isChangingConfigurations) viewModel.pause()
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
            isActive = true
        }
    }

    private fun updateMediaSession(playing: Boolean, index: Int, title: String?) {
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "FotoFesta TV")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Slideshow fotografico")
                .build()
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    index.toLong(),
                    if (playing) 1f else 0f,
                )
                .build()
        )
    }

    private fun displayName(uri: android.net.Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }
}
