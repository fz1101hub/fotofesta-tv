package it.fotofesta.tv.ui

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import it.fotofesta.tv.R
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoCaption
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.SourceType
import it.fotofesta.tv.model.TransitionType
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val CONTROLS_TIMEOUT_MS = 5_000L

/**
 * Slideshow a schermo intero. In basso restano sempre titolo, data o mittente, posizione e una
 * sottile barra di avanzamento; i comandi compaiono sopra con OK o le frecce su/giù e si nascondono
 * dopo qualche secondo mentre le foto scorrono. Con i comandi nascosti centro = play/pausa e
 * sinistra/destra = foto precedente/successiva; con i comandi visibili le frecce spostano il fuoco.
 */
@Composable
fun SlideshowScreen(
    state: AppState,
    upcoming: PhotoItem?,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSettings: () -> Unit,
) {
    val photo = state.photos.getOrNull(state.currentIndex)
    val screenFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var screenFocused by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    var focusControlsRequest by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    fun showControls(focusBar: Boolean) {
        controlsVisible = true
        if (focusBar) focusControlsRequest++
    }

    fun hideControls() {
        controlsVisible = false
        runCatching { screenFocus.requestFocus() }
    }

    LaunchedEffect(Unit) { runCatching { screenFocus.requestFocus() } }
    LaunchedEffect(focusControlsRequest) {
        if (focusControlsRequest > 0) runCatching { playFocus.requestFocus() }
    }
    LaunchedEffect(controlsVisible, interactions, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(CONTROLS_TIMEOUT_MS)
            hideControls()
        }
    }
    // Il conteggio riparte solo a un cambio di foto voluto (slideTick), non quando l'indice si sposta
    // o il numero di foto cambia perché arrivano o spariscono foto degli invitati.
    val canAdvance = state.photos.size > 1
    LaunchedEffect(state.isPlaying, state.slideTick, state.settings.intervalSeconds, canAdvance) {
        if (state.isPlaying && canAdvance) {
            delay(state.settings.intervalSeconds * 1_000L)
            onNext()
        }
    }
    PrefetchPhoto(upcoming, state.settings.fitMode, viewport)
    // Indietro dai pulsanti riporta prima il focus sulla foto (nascondendo i controlli); poi esce.
    BackHandler(enabled = !screenFocused) { hideControls() }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewport = it }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) interactions++
                false
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    // Un tasto tenuto premuto genera ripetizioni: si commuta solo alla prima pressione.
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (event.nativeKeyEvent.repeatCount == 0) onToggle(); true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { if (!state.isPlaying) onToggle(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (state.isPlaying) onToggle(); true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> { onPrevious(); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { onNext(); true }
                    // Le frecce agiscono sullo slideshow solo quando il focus non è sui pulsanti.
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                        screenFocused.also { if (it && event.nativeKeyEvent.repeatCount == 0) onToggle() }
                    KeyEvent.KEYCODE_DPAD_LEFT -> screenFocused.also { if (it) onPrevious() }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> screenFocused.also { if (it) onNext() }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU ->
                        screenFocused.also { if (it) showControls(focusBar = true) }
                    else -> false
                }
            }
            .focusRequester(screenFocus)
            .onFocusChanged { screenFocused = it.isFocused }
            .focusable()
    ) {
        if (photo != null) {
            AnimatedContent(
                targetState = photo,
                transitionSpec = {
                    if (state.settings.reducedMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else when (state.settings.transition) {
                        TransitionType.FADE -> fadeIn(tween(750)) togetherWith fadeOut(tween(750))
                        TransitionType.SLIDE -> slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            tween(650),
                        ) togetherWith slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            tween(650),
                        )
                        TransitionType.ZOOM -> (fadeIn(tween(700)) + scaleIn(tween(700), initialScale = .94f)) togetherWith
                            (fadeOut(tween(700)) + scaleOut(tween(700), targetScale = 1.04f))
                    }
                },
                label = "slideTransition",
                modifier = Modifier.fillMaxSize(),
            ) { item ->
                SlidePhoto(
                    item,
                    state.settings,
                    playing = state.isPlaying,
                    // Copre anche la transizione verso la foto successiva, così il movimento non si ferma.
                    durationMillis = state.settings.intervalSeconds * 1_000 + 800,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (state.guests.active) {
            WaitingForGuests(state.guests, Modifier.align(Alignment.Center))
        } else {
            Text(
                stringResource(R.string.slideshow_empty),
                Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
        }

        if (photo != null) {
            // Ombra in basso per la didascalia, leggibile anche su foto chiare.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.42f).background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        .45f to Color.Black.copy(alpha = .35f),
                        1f to Color.Black.copy(alpha = .82f),
                    )
                )
            )
        }

        if (photo != null && state.guests.active && state.guests.qrInSlideshow) {
            SlideshowGuestQr(state.guests, Modifier.align(Alignment.TopStart).padding(horizontal = 48.dp, vertical = 27.dp))
        }

        if (state.settings.showClock) {
            Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(horizontal = 48.dp, vertical = 27.dp)
                    .background(Background.copy(alpha = .6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) { ClockText() }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 32.dp),
        ) {
            AnimatedVisibility(
                visible = controlsVisible || !state.isPlaying,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(400)),
            ) {
                Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
                    Spacer(Modifier.width(12.dp))
                    TvIconButton(stringResource(R.string.slideshow_previous), onPrevious, Icons.Rounded.SkipPrevious)
                    Spacer(Modifier.width(12.dp))
                    TvButton(
                        if (state.isPlaying) stringResource(R.string.slideshow_pause) else stringResource(R.string.slideshow_resume),
                        onToggle,
                        modifier = Modifier.focusRequester(playFocus),
                        icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    )
                    Spacer(Modifier.width(12.dp))
                    TvIconButton(stringResource(R.string.slideshow_next), onNext, Icons.Rounded.SkipNext)
                    Spacer(Modifier.weight(1f))
                    TvButton(stringResource(R.string.action_settings), onSettings, icon = Icons.Rounded.Settings)
                }
            }
            if (photo != null) {
                Caption(photo, state)
                Spacer(Modifier.height(10.dp))
                SlideProgress(state)
            }
        }
    }
}

/**
 * Foto dello slideshow (e del salvaschermo). Con lo sfondo sfocato la foto resta intera e le bande
 * ai lati mostrano la stessa foto ingrandita e sfocata; il movimento lento riguarda solo la foto.
 */
@Composable
fun SlidePhoto(
    photo: PhotoItem,
    settings: SlideshowSettings,
    playing: Boolean,
    durationMillis: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (settings.fitMode == FitMode.BLUR) BlurredBackdrop(photo, Modifier.fillMaxSize())
        KenBurnsPhoto(
            photo,
            settings.fitMode,
            enabled = settings.kenBurns && !settings.reducedMotion,
            playing = playing,
            durationMillis = durationMillis,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Titolo (nome significativo, cartella o mittente), data o ora di arrivo, posizione nella raccolta. */
@Composable
private fun Caption(photo: PhotoItem, state: AppState) {
    val context = LocalContext.current
    val date by produceState<String?>(null, photo.id) {
        value = withContext(Dispatchers.IO) { photoDateText(context, photo) }
    }
    val title = when (photo.source) {
        SourceType.GUESTS -> photo.sender?.let { stringResource(R.string.guest_photo_from, it) } ?: stringResource(R.string.guest_photo)
        SourceType.DEMO -> photo.title
        else -> PhotoCaption.title(photo.title, (photo.ref as? PhotoRef.LocalFile)?.let { File(it.path).parentFile?.name })
    }
    val received = photo.receivedAtMillis?.let { stringResource(R.string.slideshow_received_at, rememberTimeText(it)) }
    val detail = listOfNotNull(date, received).joinToString(" · ").takeIf(String::isNotEmpty)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(20.dp))
        state.sleepAtMillis?.let { sleepAt ->
            Text(
                stringResource(R.string.slideshow_sleep_at, rememberTimeText(sleepAt)),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Accent,
                maxLines = 1,
            )
            Spacer(Modifier.width(16.dp))
        }
        Text(
            "${state.currentIndex + 1} / ${state.photos.size}",
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = TextSecondary,
            maxLines = 1,
        )
    }
}

/** Barra sottile che si riempie fino al cambio di foto; ferma e vuota in pausa. */
@Composable
private fun SlideProgress(state: AppState) {
    val progress = remember { Animatable(0f) }
    val running = state.isPlaying && state.photos.size > 1
    LaunchedEffect(state.slideTick, running, state.settings.intervalSeconds) {
        progress.snapTo(0f)
        if (running) progress.animateTo(1f, tween(state.settings.intervalSeconds * 1_000, easing = LinearEasing))
    }
    Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(TextPrimary.copy(alpha = .22f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(Accent))
    }
}

/** Data di scatto letta dai dati EXIF delle foto su disco, nel formato lungo della lingua della TV. */
private fun photoDateText(context: Context, photo: PhotoItem): String? = runCatching {
    val exif = when (val ref = photo.ref) {
        is PhotoRef.LocalFile -> ExifInterface(ref.path)
        is PhotoRef.LocalUri -> context.contentResolver.openInputStream(Uri.parse(ref.value))?.use { ExifInterface(it) }
        else -> null
    } ?: return@runCatching null
    val (year, month, day) = PhotoCaption.parseExifDate(
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
    ) ?: return@runCatching null
    val calendar = Calendar.getInstance().apply { set(year, month - 1, day, 12, 0, 0) }
    DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(calendar.time)
}.getOrNull()

/** Orario [epochMillis] nel formato 12/24 h del sistema. */
@Composable
private fun rememberTimeText(epochMillis: Long): String {
    val context = LocalContext.current
    return remember(context, epochMillis) {
        android.text.format.DateFormat.getTimeFormat(context).format(Date(epochMillis))
    }
}

/** Ora corrente nel formato 12/24 h del sistema, aggiornata allo scoccare di ogni minuto. */
@Composable
internal fun rememberCurrentTime(): String {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    var text by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(format) {
        while (true) {
            text = format.format(Date())
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    return text
}

/** Precarica la foto successiva alla risoluzione dello schermo, così la transizione non mostra caricamenti. */
@Composable
private fun PrefetchPhoto(photo: PhotoItem?, fitMode: FitMode, viewport: IntSize) {
    val context = LocalContext.current
    LaunchedEffect(photo?.id, fitMode, viewport) {
        if (photo == null || viewport == IntSize.Zero) return@LaunchedEffect
        context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(photo.ref.coilModel())
                .size(viewport.width, viewport.height)
                .scale(if (fitMode == FitMode.CROP) Scale.FILL else Scale.FIT)
                .precision(Precision.INEXACT)
                .build()
        )
    }
}
