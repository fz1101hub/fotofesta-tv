package it.fotofesta.tv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.model.TransitionType
import java.util.Date
import kotlinx.coroutines.delay

private const val CONTROLS_TIMEOUT_MS = 5_000L

/**
 * Slideshow a schermo intero. I controlli si nascondono dopo qualche secondo di inattività mentre
 * le foto scorrono; con i controlli nascosti centro = play/pausa, sinistra/destra = foto
 * precedente/successiva, su/giù mostrano i controlli. Con i controlli visibili le frecce
 * spostano normalmente il focus tra i pulsanti.
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
    LaunchedEffect(state.isPlaying, state.currentIndex, state.settings.intervalSeconds, state.photos.size) {
        if (state.isPlaying && state.photos.size > 1) {
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
                KenBurnsPhoto(
                    item,
                    state.settings.fitMode,
                    enabled = state.settings.kenBurns && !state.settings.reducedMotion,
                    playing = state.isPlaying,
                    // Copre anche la transizione verso la foto successiva, così il movimento non si ferma.
                    durationMillis = state.settings.intervalSeconds * 1_000 + 800,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Text(stringResource(R.string.slideshow_empty), Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleLarge)
        }

        AnimatedVisibility(
            visible = controlsVisible || !state.isPlaying,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .86f)))
                ).padding(horizontal = 34.dp, vertical = 24.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
                    Spacer(Modifier.size(12.dp))
                    TvButton(stringResource(R.string.slideshow_previous), onPrevious, icon = Icons.Rounded.SkipPrevious)
                    Spacer(Modifier.size(12.dp))
                    TvButton(
                        if (state.isPlaying) stringResource(R.string.slideshow_pause) else stringResource(R.string.slideshow_resume),
                        onToggle,
                        modifier = Modifier.focusRequester(playFocus),
                        icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        selected = state.isPlaying,
                    )
                    Spacer(Modifier.size(12.dp))
                    TvButton(stringResource(R.string.slideshow_next), onNext, icon = Icons.Rounded.SkipNext)
                    Spacer(Modifier.size(18.dp))
                    // Il titolo prende solo lo spazio rimasto: i pulsanti non vengono mai schiacciati.
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(
                            photo?.title.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.slideshow_info, state.currentIndex + 1, state.photos.size, state.settings.intervalSeconds, stringResource(state.settings.transition.labelRes)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        state.sleepAtMillis?.let { sleepAt ->
                            Text(
                                stringResource(R.string.slideshow_sleep_at, rememberTimeText(sleepAt)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Amber,
                            )
                        }
                    }
                    Spacer(Modifier.size(18.dp))
                    TvButton(stringResource(R.string.action_settings), onSettings, icon = Icons.Rounded.Settings)
                }
            }
        }

        if (state.settings.showClock) {
            Text(
                rememberCurrentTime(),
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = .5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

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
        if (photo == null || photo.ref is PhotoRef.Resource || viewport == IntSize.Zero) return@LaunchedEffect
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
