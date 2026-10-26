package it.fotofesta.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var controlsVisible by remember { mutableStateOf(!state.isPlaying) }

    LaunchedEffect(Unit) { screenFocus.requestFocus() }
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) controlsVisible = true
    }
    // Hide controls while playing; leave them visible when paused.
    LaunchedEffect(controlsVisible, state.isPlaying, state.currentIndex) {
        if (controlsVisible && state.isPlaying) {
            delay(4_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) screenFocus.requestFocus()
    }
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
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER -> {
                        if (!controlsVisible) { onToggle(); controlsVisible = true; true } else false
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        onToggle(); controlsVisible = true; true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!controlsVisible) { controlsVisible = true; true } else false
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (!controlsVisible || event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                            onPrevious(); true
                        } else false
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (!controlsVisible || event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_NEXT) {
                            onNext(); true
                        } else false
                    }
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
                            AnimatedContentTransitionScope.SlideDirection.Left, tween(650)
                        ) togetherWith slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left, tween(650)
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

        if (state.settings.showClock) {
            Text(
                SimpleDateFormat("HH:mm", Locale.ITALY).format(Date()),
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
                    .background(Color.Black.copy(alpha = .65f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        AnimatedVisibility(
            visible = controlsVisible || !state.isPlaying,
            enter = if (state.settings.reducedMotion) EnterTransition.None else fadeIn(tween(180)),
            exit = if (state.settings.reducedMotion) ExitTransition.None else fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (state.settings.highContrast) 1f else .94f)))
                ).padding(horizontal = 40.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(photo?.title.orEmpty(), style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${if (state.photos.isEmpty()) 0 else state.currentIndex + 1} / ${state.photos.size}  ·  ${state.settings.intervalSeconds}s  ·  ${state.settings.transition.label}",
                    style = MaterialTheme.typography.bodyMedium, color = GraphiteSecondary,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Indietro", onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack, reducedMotion = state.settings.reducedMotion)
                    TvButton("Precedente", onPrevious, icon = Icons.Rounded.SkipPrevious, reducedMotion = state.settings.reducedMotion)
                    TvButton(
                        if (state.isPlaying) "Pausa" else "Riprendi",
                        { onToggle(); controlsVisible = true },
                        icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        primary = true,
                        reducedMotion = state.settings.reducedMotion,
                    )
                    TvButton("Successiva", onNext, icon = Icons.Rounded.SkipNext, reducedMotion = state.settings.reducedMotion)
                    Spacer(Modifier.weight(1f))
                    TvButton("Opzioni", onSettings, icon = Icons.Rounded.Settings, reducedMotion = state.settings.reducedMotion)
                }
            }
        }
    }
}
