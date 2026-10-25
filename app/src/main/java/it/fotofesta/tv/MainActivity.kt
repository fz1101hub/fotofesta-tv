package it.fotofesta.tv

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.ui.Accent
import it.fotofesta.tv.ui.AwardsScreen
import it.fotofesta.tv.ui.FileBrowserScreen
import it.fotofesta.tv.ui.FotoFestaTheme
import it.fotofesta.tv.ui.GamesScreen
import it.fotofesta.tv.ui.GoogleConnectScreen
import it.fotofesta.tv.ui.GuestReviewScreen
import it.fotofesta.tv.ui.GuestWifiScreen
import it.fotofesta.tv.ui.AnnounceScreen
import it.fotofesta.tv.ui.GuestbookScreen
import it.fotofesta.tv.ui.LotteryScreen
import it.fotofesta.tv.ui.PollScreen
import it.fotofesta.tv.ui.PortraitFrame
import it.fotofesta.tv.ui.ShowsScreen
import it.fotofesta.tv.ui.RewindScreen
import it.fotofesta.tv.ui.BarcodeScreen
import it.fotofesta.tv.ui.PhotoMosaicScreen
import it.fotofesta.tv.ui.CreditsScreen
import it.fotofesta.tv.ui.TeletextScreen
import it.fotofesta.tv.ui.SkyScreen
import it.fotofesta.tv.ui.StatsScreen
import it.fotofesta.tv.ui.PartyGamesScreen
import it.fotofesta.tv.ui.PlayScreen
import it.fotofesta.tv.ui.TournamentScreen
import it.fotofesta.tv.ui.BetsScreen
import it.fotofesta.tv.ui.ToastScreen
import it.fotofesta.tv.ui.CoinsScreen
import it.fotofesta.tv.ui.ICloudLinkScreen
import it.fotofesta.tv.ui.LocalReducedMotion
import it.fotofesta.tv.ui.GuestsScreen
import it.fotofesta.tv.ui.HomeScreen
import it.fotofesta.tv.ui.MemoryGameScreen
import it.fotofesta.tv.ui.MysteryGameScreen
import it.fotofesta.tv.ui.Outline
import it.fotofesta.tv.ui.PuzzleGameScreen
import it.fotofesta.tv.ui.SettingsScreen
import it.fotofesta.tv.ui.SlideshowScreen
import it.fotofesta.tv.ui.SmbLoginScreen
import it.fotofesta.tv.ui.SurfaceRaised
import it.fotofesta.tv.ui.TextPrimary
import it.fotofesta.tv.ui.WhoTookItScreen
import it.fotofesta.tv.ui.PhonePlay
import it.fotofesta.tv.ui.BuzzerScreen
import it.fotofesta.tv.ui.DoorScreen
import it.fotofesta.tv.ui.ExtraScreen
import it.fotofesta.tv.guests.ExtraScreenClient
import it.fotofesta.tv.ui.CameraScreen
import it.fotofesta.tv.ui.projectorBoost
import it.fotofesta.tv.tv.TvFeatures
import it.fotofesta.tv.store.StoreUpdates
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: FotoFestaViewModel by viewModels()
    private lateinit var mediaSession: MediaSessionCompat

    // Aggiornamenti dallo store (Play Store; sull'Amazon Appstore li gestisce Fire OS).
    private val storeUpdates = StoreUpdates(this)

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
        lifecycleScope.launch {
            viewModel.state
                .map { it.destination == Destination.HOME && !it.guests.active }
                .distinctUntilChanged()
                .collect { free -> storeUpdates.setIdle(free) }
        }
        storeUpdates.start()

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
                        StoragePermissions.granted(this@MainActivity, kind) -> viewModel.openBrowser(kind, systemPickerAvailable(kind))
                        // Le foto dei server della rete di casa non chiedono permessi: il browser si apre lo stesso.
                        kind == BrowserKind.PHOTOS -> {
                            viewModel.openBrowser(kind, systemPickerAvailable(kind))
                            viewModel.showStoragePermissionDenied(kind)
                        }
                        // Senza permesso resta il selettore di sistema, se la TV lo ha.
                        systemPickerAvailable(kind) -> launchSystemPicker(kind, { usbPicker.launch(null) }, { musicPicker.launch(arrayOf("audio/*")) })
                        else -> {
                            viewModel.showStoragePermissionDenied(kind)
                            // Rifiutato per sempre: il dialogo non comparirebbe più, si aprono le impostazioni dell'app.
                            if (result.keys.none { shouldShowRequestPermissionRationale(it) }) openAppSettings()
                        }
                    }
                }
                val usbBackupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    uri?.let(viewModel::setUsbBackup)
                }
                // Rete della TV: Android chiede il permesso per i dispositivi Wi‑Fi vicini (13+) o la posizione.
                val hotspotPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                    if (result.isEmpty()) return@rememberLauncherForActivityResult
                    if (result.values.all { it }) viewModel.toggleHotspot() else viewModel.showHotspotPermissionDenied()
                }
                fun toggleHotspot() {
                    val missing = hotspotPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (viewModel.state.value.guests.hotspot || missing.isEmpty()) viewModel.toggleHotspot()
                    else hotspotPermission.launch(missing.toTypedArray())
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
                    Destination.GUEST_REVIEW, Destination.AWARDS, Destination.GUESTBOOK, Destination.ANNOUNCE -> true
                    Destination.POLL, Destination.LOTTERY -> true
                    Destination.PARTY_GAMES, Destination.PLAY, Destination.TOURNAMENT, Destination.BETS,
                    Destination.TOAST, Destination.COINS -> true
                    Destination.SHOWS, Destination.REWIND, Destination.BARCODE, Destination.PHOTO_MOSAIC, Destination.CREDITS,
                    Destination.TELETEXT, Destination.SKY, Destination.STATS -> true
                    Destination.BUZZER, Destination.CAMERA, Destination.DOOR, Destination.EXTRA_SCREEN -> true
                    // Il QR per incollare il link dell'album deve restare visibile.
                    Destination.ICLOUD_LINK -> true
                    else -> false
                }
                DisposableEffect(keepScreenOn) {
                    if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose {
                        if (keepScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // Modalità gioco della TV durante i giochi: risposte e disegni arrivano senza ritardo.
                val lowLatency = state.destination in LOW_LATENCY_DESTINATIONS
                DisposableEffect(lowLatency) {
                    TvFeatures.setLowLatency(window, lowLatency)
                    onDispose { if (lowLatency) TvFeatures.setLowLatency(window, false) }
                }

                LaunchedEffect(state.message) {
                    if (state.message != null) {
                        delay(5_000)
                        viewModel.clearMessage()
                    }
                }

                CompositionLocalProvider(LocalReducedMotion provides state.settings.reducedMotion) {
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
                                onPickUsbBackup = { launchPicker { usbBackupPicker.launch(null) } },
                                onRemoveUsbBackup = viewModel::removeUsbBackup,
                            )
                            Destination.SLIDESHOW -> Box(Modifier.fillMaxSize().projectorBoost(state.settings.projectorMode)) { PortraitFrame(state.settings.portrait) { SlideshowScreen(
                                state = state.copy(settings = state.settings.forScreen()),
                                upcoming = viewModel.upcomingPhoto(),
                                onBack = viewModel::back,
                                onToggle = viewModel::togglePlayback,
                                onNext = viewModel::next,
                                onPrevious = viewModel::previous,
                                onSettings = { viewModel.pause(); viewModel.navigate(Destination.SETTINGS) },
                                onVideoAudible = viewModel::setVideoAudible,
                                reactions = viewModel.reactions,
                                arrivals = viewModel.arrivals,
                                photoAge = viewModel::photoAge,
                                photoTag = viewModel::photoTag,
                            ) } }
                            Destination.GAMES -> GamesScreen(
                                // I giochi usano solo le foto: i video si guardano nello slideshow.
                                photoCount = state.photos.count { !it.video },
                                whoTookItPlayable = WhoTookIt.canPlay(state.photos),
                                onBack = { viewModel.navigate(Destination.HOME) },
                                onMemory = { viewModel.navigate(Destination.MEMORY) },
                                onMystery = { viewModel.navigate(Destination.MYSTERY) },
                                onPuzzle = { viewModel.navigate(Destination.PUZZLE) },
                                onWhoTookIt = { viewModel.navigate(Destination.WHO_TOOK_IT) },
                                partyActive = state.guests.active && state.guests.url != null,
                                onPoll = { viewModel.navigate(Destination.POLL) },
                                onLottery = { viewModel.navigate(Destination.LOTTERY) },
                                onDirector = viewModel::startDirector,
                                onQuiet = viewModel::startQuiet,
                                onPartyGames = { viewModel.navigate(Destination.PARTY_GAMES) },
                                onShows = { viewModel.navigate(Destination.SHOWS) },
                                onBuzzer = { viewModel.navigate(Destination.BUZZER) },
                                onCamera = { viewModel.navigate(Destination.CAMERA) },
                                silentDisco = state.silentDisco,
                                onSilentDisco = viewModel::toggleSilentDisco,
                            )
                            Destination.BUZZER -> BuzzerScreen(onBack = viewModel::back)
                            Destination.CAMERA -> CameraScreen(
                                camera = viewModel.ipCamera,
                                savedAddress = state.cameraAddress,
                                partyActive = state.guests.active,
                                onSaveAddress = viewModel::saveCameraAddress,
                                onSnapshot = viewModel::addCameraPhoto,
                                onBack = viewModel::back,
                            )
                            Destination.SHOWS -> ShowsScreen(
                                onRewind = { viewModel.navigate(Destination.REWIND) },
                                onBarcode = { viewModel.navigate(Destination.BARCODE) },
                                onMosaic = { viewModel.navigate(Destination.PHOTO_MOSAIC) },
                                onCredits = { viewModel.navigate(Destination.CREDITS) },
                                onTeletext = { viewModel.navigate(Destination.TELETEXT) },
                                onSky = { viewModel.navigate(Destination.SKY) },
                                onStats = { viewModel.navigate(Destination.STATS) },
                                enoughPhotos = state.photos.count { !it.video } >= 2,
                                onBack = { viewModel.navigate(Destination.GAMES) },
                            )
                            Destination.REWIND -> RewindScreen(remember { state.photos }, onBack = viewModel::back)
                            Destination.BARCODE -> BarcodeScreen(remember { state.photos }, onBack = viewModel::back)
                            Destination.PHOTO_MOSAIC -> PhotoMosaicScreen(remember { state.photos }, onBack = viewModel::back)
                            Destination.CREDITS -> CreditsScreen(
                                title = stringResource(state.settings.template.titleRes),
                                sections = remember { viewModel.credits() },
                                music = state.musicName,
                                onBack = viewModel::back,
                            )
                            Destination.TELETEXT -> TeletextScreen(
                                title = stringResource(state.settings.template.titleRes),
                                messages = state.party.messages,
                                coins = state.play.coins,
                                stats = remember(state.photos, state.party.messages) { viewModel.partyStats() },
                                onBack = viewModel::back,
                            )
                            Destination.SKY -> SkyScreen(viewModel.lotteryCandidates(), state.guests.shown, onBack = viewModel::back)
                            Destination.STATS -> {
                                val stats = remember(state.photos, state.party.messages) { viewModel.partyStats() }
                                StatsScreen(stats, state.photos.firstOrNull { it.id == stats.favorite }, onBack = viewModel::back)
                            }
                            Destination.PARTY_GAMES -> PartyGamesScreen(
                                partyActive = state.guests.active && state.guests.url != null,
                                play = state.play,
                                onStart = { viewModel.startPlay(it) },
                                onTournament = { viewModel.navigate(Destination.TOURNAMENT) },
                                onBets = { viewModel.navigate(Destination.BETS) },
                                onToast = { viewModel.navigate(Destination.TOAST) },
                                onCoins = { viewModel.navigate(Destination.COINS) },
                                onTreasure = viewModel::startTreasure,
                                onLights = viewModel::setLights,
                                onBack = { viewModel.navigate(Destination.GAMES) },
                            )
                            Destination.PLAY -> PlayScreen(
                                play = state.play,
                                url = state.guests.url,
                                photos = viewModel::playPhotos,
                                original = viewModel.recreateOriginal,
                                onReveal = viewModel::playReveal,
                                onVote = viewModel::playVoting,
                                onAgain = viewModel::playAgain,
                                onBack = viewModel::back,
                            )
                            Destination.TOURNAMENT -> {
                                val photos = remember { state.photos.filterNot { it.video } }
                                TournamentScreen(photos, phonePlay(state), onBack = viewModel::back)
                            }
                            Destination.BETS -> BetsScreen(phonePlay(state), viewModel::lotteryCandidates, viewModel::settleBet, onBack = viewModel::back)
                            Destination.TOAST -> ToastScreen(viewModel::lotteryCandidates, viewModel::announceToast, onBack = viewModel::back)
                            Destination.COINS -> CoinsScreen(state.play.coins, onBack = viewModel::back)
                            Destination.POLL -> PollScreen(phonePlay(state), viewModel::lotteryCandidates) { viewModel.navigate(Destination.GAMES) }
                            Destination.LOTTERY -> LotteryScreen(viewModel::lotteryCandidates) { viewModel.navigate(Destination.GAMES) }
                            // Le partite usano le foto presenti all'apertura del gioco: quelle che arrivano
                            // dagli invitati durante la partita non la fanno ricominciare.
                            Destination.MEMORY -> {
                                val photos = remember { state.photos.filterNot { it.video } }
                                MemoryGameScreen(photos) { viewModel.navigate(Destination.GAMES) }
                            }
                            Destination.MYSTERY -> {
                                val photos = remember { state.photos.filterNot { it.video } }
                                MysteryGameScreen(photos, phonePlay(state)) { viewModel.navigate(Destination.GAMES) }
                            }
                            Destination.PUZZLE -> {
                                val photos = remember { state.photos.filterNot { it.video } }
                                PuzzleGameScreen(photos) { viewModel.navigate(Destination.GAMES) }
                            }
                            Destination.WHO_TOOK_IT -> {
                                val photos = remember { state.photos }
                                WhoTookItScreen(photos, phonePlay(state)) { viewModel.navigate(Destination.GAMES) }
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
                                messageCount = state.party.messages.size,
                                pendingMessageCount = state.party.pendingMessages.size,
                                onGuestbook = { viewModel.navigate(Destination.GUESTBOOK) },
                                hostPin = state.party.hostPin,
                                onAnnounce = { viewModel.navigate(Destination.ANNOUNCE) },
                                undevelopedShots = state.party.shotsTaken.takeIf { state.party.undeveloped },
                                onDevelop = viewModel::develop,
                                onRehearsal = viewModel::startRehearsal,
                                onHotspot = ::toggleHotspot,
                                onDoor = { viewModel.navigate(Destination.DOOR) },
                                onExtraScreen = { viewModel.navigate(Destination.EXTRA_SCREEN) },
                            )
                            Destination.EXTRA_SCREEN -> ExtraScreen(remember { ExtraScreenClient(appServices.lanHttp) }, onBack = viewModel::back)
                            Destination.DOOR -> DoorScreen(state.guests.url, state.party.arrivals, onBack = viewModel::back)
                            Destination.AWARDS -> AwardsScreen(
                                awards = remember(state.photos) { PartyAwards.from(state.photos, viewModel.reactionCounts()) },
                                onBack = { viewModel.navigate(Destination.GUESTS) },
                                // La copia sulla chiavetta: su Fire TV di solito non c'è una porta USB.
                                onCapsule = viewModel::sealCapsule.takeIf { state.usbBackupName != null && !state.settings.noTraces },
                            )
                            Destination.ANNOUNCE -> AnnounceScreen(
                                onAnnounce = { viewModel.announce(it) },
                                onBack = { viewModel.navigate(Destination.GUESTS) },
                            )
                            Destination.GUESTBOOK -> GuestbookScreen(
                                party = state.party,
                                onApprove = viewModel::approveMessage,
                                onDiscard = viewModel::discardMessage,
                                onApproveAll = viewModel::approveAllMessages,
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
                                onUseMusicFolder = viewModel::browserUseMusicFolder,
                                onSystemPicker = {
                                    launchSystemPicker(state.browser.kind, { usbPicker.launch(null) }, { musicPicker.launch(arrayOf("audio/*")) })
                                },
                                onSearchNetwork = viewModel::browserSearchNetwork,
                                onICloud = viewModel::openICloudLink,
                                onSmbAdd = { viewModel.openSmbLogin() },
                                onBack = viewModel::closeBrowser,
                                icloudAlbum = state.icloud.savedName,
                            )
                            Destination.ICLOUD_LINK -> ICloudLinkScreen(
                                state = state.icloud,
                                onSubmit = viewModel::previewICloudAlbum,
                                onUse = viewModel::useICloudAlbum,
                                onBack = viewModel::closeICloudLink,
                            )
                            Destination.SMB_LOGIN -> SmbLoginScreen(
                                state = state.smb,
                                onConnect = viewModel::smbConnect,
                                onBack = viewModel::closeSmbLogin,
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
        // Solo nelle build di debug: schermate automatiche nell'emulatore della CI (src/debug).
        if (savedInstanceState == null) {
            ScreenshotScenario.start(this, viewModel, intent)
            handleIntent(intent)
        }
    }

    /** Avvio automatico all'accensione e scorciatoie del canale nella schermata Home. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            viewModel.playCurrent()
            return
        }
        val link = intent.data
        if (intent.action == Intent.ACTION_VIEW && link?.scheme == "fotofesta" && link.host == "open") {
            viewModel.openLink(link.lastPathSegment.orEmpty())
        }
    }

    private fun hotspotPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        // Android 12 chiede le due insieme; l'hotspot vuole la posizione precisa.
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Gioco con i telefoni: solo durante la festa, quando la pagina degli invitati è raggiungibile. */
    @Composable
    private fun phonePlay(state: AppState): PhonePlay? {
        val url = state.guests.url?.takeIf { state.guests.active }
        return remember(url) { url?.let { PhonePlay(viewModel.phoneGame, it) } }
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
        handleIntent(intent)
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
                // Alexa e alcuni telecomandi traducono "avanti" e "indietro" in avanti e indietro veloce.
                override fun onFastForward() = viewModel.next()
                override fun onRewind() = viewModel.previous()
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
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND
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

    companion object {
        /** Intent dell'avvio automatico all'accensione della TV (BootReceiver). */
        const val EXTRA_AUTO_START = "it.fotofesta.tv.AUTO_START"

        private val LOW_LATENCY_DESTINATIONS = setOf(
            Destination.MEMORY, Destination.MYSTERY, Destination.PUZZLE, Destination.WHO_TOOK_IT, Destination.POLL,
            Destination.PLAY, Destination.TOURNAMENT, Destination.BETS, Destination.BUZZER, Destination.CAMERA,
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    override fun onDestroy() {
        storeUpdates.stop()
        mediaSession.release()
        super.onDestroy()
    }
}
