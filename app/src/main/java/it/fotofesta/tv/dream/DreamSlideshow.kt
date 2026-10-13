package it.fotofesta.tv.dream

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PlaybackOrder
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.ui.KenBurnsPhoto
import it.fotofesta.tv.ui.rememberCurrentTime
import kotlinx.coroutines.delay

private const val DREAM_FADE_MS = 1_200

/**
 * Slideshow del salvaschermo: nessun controllo, dissolvenza lenta e, se attivi nelle impostazioni,
 * movimento lento e orologio. L'orologio cambia angolo ogni minuto per non segnare gli schermi OLED.
 */
@Composable
fun DreamSlideshow(settings: SlideshowSettings, loadPhotos: suspend () -> List<PhotoItem>) {
    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    LaunchedEffect(Unit) { photos = loadPhotos() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (photos.isNotEmpty()) {
            val order = remember(photos) { PlaybackOrder() }
            var index by remember(photos) {
                val start = if (settings.shuffle) photos.indices.random() else 0
                order.reset(photos.size, settings.shuffle, start)
                mutableIntStateOf(start)
            }
            LaunchedEffect(photos, index) {
                if (photos.size > 1) {
                    delay(settings.intervalSeconds * 1_000L)
                    index = order.next(index)
                }
            }
            AnimatedContent(
                targetState = photos[index],
                transitionSpec = {
                    if (settings.reducedMotion) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(DREAM_FADE_MS)) togetherWith fadeOut(tween(DREAM_FADE_MS))
                },
                label = "dreamTransition",
                modifier = Modifier.fillMaxSize(),
            ) { item ->
                KenBurnsPhoto(
                    item,
                    settings.fitMode,
                    enabled = settings.kenBurns && !settings.reducedMotion,
                    playing = true,
                    durationMillis = settings.intervalSeconds * 1_000 + DREAM_FADE_MS,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (settings.showClock) {
            val time = rememberCurrentTime()
            val corner = remember(time) { CLOCK_CORNERS[(time.hashCode() and Int.MAX_VALUE) % CLOCK_CORNERS.size] }
            Text(
                time,
                modifier = Modifier.align(corner)
                    .padding(40.dp)
                    .background(Color.Black.copy(alpha = .45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White.copy(alpha = .85f),
            )
        }
    }
}

private val CLOCK_CORNERS = listOf(Alignment.TopEnd, Alignment.BottomStart, Alignment.TopStart, Alignment.BottomEnd)
