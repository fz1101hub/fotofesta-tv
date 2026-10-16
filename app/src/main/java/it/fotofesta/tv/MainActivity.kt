package it.fotofesta.tv

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
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
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import it.fotofesta.tv.files.StoragePermissions
import it.fotofesta.tv.model.BrowserKind
import it.fotofesta.tv.model.Destination
import it.fotofesta.tv.model.GoogleConnectState
import it.fotofesta.tv.model.PartyAwards
import it.fotofesta.tv.model.SourceType
import it.fotofesta.tv.model.WhoTookIt
import it.fotofesta.tv.ui.Accent
import it.fotofesta.tv.ui.AwardsScreen
import it.fotofesta.tv.ui.FileBrowserScreen
import it.fotofesta.tv.ui.FotoFestaTheme
import it.fotofesta.tv.ui.GamesScreen
import it.fotofesta.tv.ui.GoogleConnectScreen
import it.fotofesta.tv.ui.GuestReviewScreen
import it.fotofesta.tv.ui.GuestWifiScreen
import it.fotofesta.tv.ui.GuestsScreen
import it.fotofesta.tv.ui.HomeScreen
import it.fotofesta.tv.ui.MemoryGameScreen
import it.fotofesta.tv.ui.MysteryGameScreen
import it.fotofesta.tv.ui.Outline
import it.fotofesta.tv.ui.PuzzleGameScreen
import it.fotofesta.tv.ui.SettingsScreen
import it.fotofesta.tv.ui.SlideshowScreen
import it.fotofesta.tv.ui.SurfaceRaised
import it.fotofesta.tv.ui.TextPrimary
import it.fotofesta.tv.ui.WhoTookItScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: FotoFestaViewModel by viewModels()
    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setupMediaSession()
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
                    uri?.let {
                        // Prima si chiude il browser: la navigazione toglierebbe il messaggio di avanzamento.
                        if (viewModel.state.value.destination == Destination.FILE_BROWSER) viewModel.closeBrowser()
                        viewModel.loadUsb(it)
                    }
                }
                val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let {
                        if (viewModel.state.value.destination == Destination.FILE_BROWSER) viewModel.closeBrowser()
                        viewModel.selectMusic(it, displayName(it))
                    }
                }
                // Il selettore di documenti di Android manca su molte TV: cartelle e brani si scelgono
                // con il browser interno, che chiede il permesso di leggere foto o musica.
                var pendingBrowser by rememberSaveable { mutableStateOf<BrowserKind?>(null) }
                val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                    // Risultato vuoto: richiesta ripetuta (doppia pressione) mentre il dialogo è aperto.
                    // La risposta vera arriva dopo, quindi qui non si decide nulla.
                    if (result.isEmpty()) return@rememberLauncherForActivityResult
                    val kind = pendingBrowser ?: return@rememberLauncherForActivityResult
                    pendingBrowser = null
                    when {
                        result.values.all { it } -> viewModel.openBrowser(kind, systemPickerAvailable(kind))
                        // Senza permesso resta il selettore di sistema, se la TV lo ha.
                        systemPickerAvailable(kind) -> launchSystemPicker(kind, { usbPicker.launch(null) }, { musicPicker.launch(arrayOf("audio/*")) })
                        else -> {
                            viewModel.showStoragePermissionDenied(kind)
                            // Rifiutato per sempre: il dialogo non comparirebbe più, si aprono le impostazioni dell'app.
                            if (result.keys.none { shouldShowRequestPermissionRationale(it) }) openAppSettings()
                        }
                    }
                }
                fun openBrowser(kind: BrowserKind) {
                    val missing = StoragePermissions.missing(this, kind)
                    if (missing.isEmpty()) {
                        viewModel.openBrowser(kind, systemPickerAvailable(kind))
                    } else {
                        pendingBrowser = kind
                        permissionRequest.launch(missing.toTypedArray())
                    }
                }

                BackHandler(enabled = state.destination != Destination.HOME, onBack = viewModel::back)

                // Lo schermo resta acceso durante slideshow e giochi, ma uno slideshow in pausa
                // (anche per la pausa automatica) lascia che la TV vada in standby. Durante il
                // collegamento a Google il QR e il codice devono restare visibili mentre si usa il telefono.
                val keepScreenOn = when (state.destination) {
                    Destination.SLIDESHOW -> state.isPlaying
                    Destination.MEMORY, Destination.MYSTERY, Destination.PUZZLE, Destination.WHO_TOOK_IT -> true
                    Destination.GOOGLE_CONNECT ->
                        state.google !is GoogleConnectState.Error && state.google !is GoogleConnectState.Complete
                    // Durante la festa il QR deve restare visibile mentre gli invitati lo inquadrano.
                    Destination.GUESTS -> state.guests.active
                    Destination.GUEST_REVIEW, Destination.AWARDS -> true
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
                            showGoogle = viewModel.showGoogle,
                            onGoogle = viewModel::startGoogleConnect,
                            onGuests = { viewModel.navigate(Destination.GUESTS) },
                            onUsb = { openBrowser(BrowserKind.PHOTOS) },
                            onDemo = viewModel::useDemoAndPlay,
                            onPlay = viewModel::playCurrent,
                            onStartParty = viewModel::startPartyAndPlay,
                            onSettings = { viewModel.navigate(Destination.SETTINGS) },
                            onGames = { viewModel.navigate(Destination.GAMES) },
                        )
                        Destination.SETTINGS -> SettingsScreen(
                            state = state,
                            onCategory = viewModel::setSettingsCategory,
                            onChange = viewModel::updateSettings,
                            onPickMusic = { openBrowser(BrowserKind.MUSIC) },
                            onRemoveMusic = viewModel::removeMusic,
                            onDisconnectGoogle = viewModel::disconnectGoogle,
                            onOpenScreensaverSettings = ::openScreensaverSettings,
                            onApproval = viewModel::setGuestApproval,
                            onQrInSlideshow = viewModel::setGuestQrInSlideshow,
                            onWifi = viewModel::openGuestWifi,
                            onRemoveWifi = viewModel::removeGuestWifi,
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
                            whoTookItPlayable = WhoTookIt.canPlay(state.photos),
                            onBack = { viewModel.navigate(Destination.HOME) },
                            onMemory = { viewModel.navigate(Destination.MEMORY) },
                            onMystery = { viewModel.navigate(Destination.MYSTERY) },
                            onPuzzle = { viewModel.navigate(Destination.PUZZLE) },
                            onWhoTookIt = { viewModel.navigate(Destination.WHO_TOOK_IT) },
                        )
                        // Le partite usano le foto presenti all'apertura del gioco: quelle che arrivano
                        // dagli invitati durante la partita non la fanno ricominciare.
                        Destination.MEMORY -> {
                            val photos = remember { state.photos }
                            MemoryGameScreen(photos) { viewModel.navigate(Destination.GAMES) }
                        }
                        Destination.MYSTERY -> {
                            val photos = remember { state.photos }
                            MysteryGameScreen(photos) { viewModel.navigate(Destination.GAMES) }
                        }
                        Destination.PUZZLE -> {
                            val photos = remember { state.photos }
                            PuzzleGameScreen(photos) { viewModel.navigate(Destination.GAMES) }
                        }
                        Destination.WHO_TOOK_IT -> {
                            val photos = remember { state.photos }
                            WhoTookItScreen(photos) { viewModel.navigate(Destination.GAMES) }
                        }
                        Destination.GOOGLE_CONNECT -> GoogleConnectScreen(
                            state = state.google,
                            onCancel = viewModel::cancelGoogleConnect,
                            onRetry = viewModel::startGoogleConnect,
                            onPlay = viewModel::playCurrent,
                        )
                        Destination.GUESTS -> GuestsScreen(
                            guests = state.guests,
                            // Le ultime foto arrivate, dalla più recente.
                            recent = state.photos.filter { it.source == SourceType.GUESTS }
                                .sortedByDescending { it.receivedAtMillis ?: 0L }
                                .take(4),
                            onStart = viewModel::startGuestParty,
                            onEnd = viewModel::endGuestParty,
                            onPlay = viewModel::playCurrent,
                            onReview = { viewModel.navigate(Destination.GUEST_REVIEW) },
                            onAwards = { viewModel.navigate(Destination.AWARDS) },
                            onApproval = viewModel::setGuestApproval,
                            onWifi = viewModel::openGuestWifi,
                        )
                        Destination.AWARDS -> AwardsScreen(
                            awards = remember(state.photos) { PartyAwards.from(state.photos) },
                            onBack = { viewModel.navigate(Destination.GUESTS) },
                        )
                        Destination.GUEST_REVIEW -> GuestReviewScreen(
                            pending = state.guests.pending,
                            onBack = { viewModel.navigate(Destination.GUESTS) },
                            onShow = viewModel::approveGuestPhoto,
                            onDiscard = viewModel::discardGuestPhoto,
                            onApproveAll = viewModel::approveAllGuestPhotos,
                        )
                        Destination.FILE_BROWSER -> FileBrowserScreen(
                            state = state.browser,
                            onOpen = viewModel::browserOpen,
                            onUp = viewModel::browserUp,
                            onUseFolder = viewModel::browserUseFolder,
                            onChooseTrack = viewModel::browserChooseTrack,
                            onSystemPicker = {
                                launchSystemPicker(state.browser.kind, { usbPicker.launch(null) }, { musicPicker.launch(arrayOf("audio/*")) })
                            },
                            onBack = viewModel::closeBrowser,
                        )
                        Destination.GUEST_WIFI -> GuestWifiScreen(
                            initialName = state.guests.wifiName,
                            initialPassword = state.guests.wifiPassword,
                            onSave = viewModel::saveGuestWifi,
                        )
                    }

                    // Messaggi e avanzamento in sovrimpressione, in basso: in alto coprirebbero
                    // l'intestazione con la sorgente e l'indicatore della festa.
                    if (state.message != null || state.busy) {
                        Column(
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .padding(bottom = 28.dp)
                                .widthIn(max = 760.dp)
                                .shadow(16.dp, RoundedCornerShape(14.dp))
                                .background(SurfaceRaised, RoundedCornerShape(14.dp))
                                .border(1.dp, Outline, RoundedCornerShape(14.dp))
                                .padding(horizontal = 22.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            state.message?.let { message ->
                                Text(
                                    message,
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            if (state.busy) {
                                LinearProgressIndicator(Modifier.width(320.dp), color = Accent, trackColor = TextPrimary.copy(alpha = .2f))
                            }
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

    private fun launchSystemPicker(kind: BrowserKind, photos: () -> Unit, music: () -> Unit) =
        launchPicker(if (kind == BrowserKind.PHOTOS) photos else music)

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: ActivityNotFoundException) {
            // Alcune TV non hanno la pagina dell'app: resta il messaggio con il percorso nelle impostazioni.
        }
    }

    /** La TV ha il selettore di documenti di Android (molte non ce l'hanno). */
    private fun systemPickerAvailable(kind: BrowserKind): Boolean {
        val intent = if (kind == BrowserKind.PHOTOS) {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
        }
        return runCatching { intent.resolveActivity(packageManager) != null }.getOrDefault(false)
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
        mediaSession.isActive = true
    }

    override fun onStop() {
        // Con l'app in background i tasti multimediali non devono avviare musica senza interfaccia.
        mediaSession.isActive = false
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
        }
    }

    private fun updateMediaSession(playing: Boolean, title: String?) {
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, getString(R.string.slideshow_session_subtitle))
                .build()
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                )
                .build()
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }
}
