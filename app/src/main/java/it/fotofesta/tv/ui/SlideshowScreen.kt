package it.fotofesta.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.TransitionType
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SlideshowScreen(
    state: AppState,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSettings: () -> Unit,
) {
    val photo = state.photos.getOrNull(state.currentIndex)
    val screenFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { screenFocus.requestFocus() }
    LaunchedEffect(state.isPlaying, state.currentIndex, state.settings.intervalSeconds, state.photos.size) {
        if (state.isPlaying && state.photos.size > 1) {
            delay(state.settings.intervalSeconds * 1_000L)
            onNext()
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .focusRequester(screenFocus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { onToggle(); true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { onPrevious(); true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { onNext(); true }
                    else -> false
                }
            }
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
                PhotoContent(item, state.settings.fitMode, Modifier.fillMaxSize())
            }
        }

        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .86f)))
            ).padding(horizontal = 34.dp, vertical = 24.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TvButton("Indietro", onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
                Spacer(Modifier.size(12.dp))
                TvButton("Precedente", onPrevious, icon = Icons.Rounded.SkipPrevious)
                Spacer(Modifier.size(12.dp))
                TvButton(
                    if (state.isPlaying) "Pausa" else "Riprendi",
                    onToggle,
                    icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    selected = state.isPlaying,
                )
                Spacer(Modifier.size(12.dp))
                TvButton("Successiva", onNext, icon = Icons.Rounded.SkipNext)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(photo?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${state.currentIndex + 1} / ${state.photos.size}  ·  ${state.settings.intervalSeconds}s  ·  ${state.settings.transition.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.size(18.dp))
                TvButton("Impostazioni", onSettings, icon = Icons.Rounded.Settings)
            }
        }

        if (state.settings.showClock) {
            Text(
                SimpleDateFormat("HH:mm", Locale.ITALY).format(Date()),
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color.Black.copy(alpha = .5f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
