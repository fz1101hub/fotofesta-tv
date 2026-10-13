package it.fotofesta.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.KenBurnsMotion
import it.fotofesta.tv.model.PhotoItem

/**
 * Foto con movimento lento (zoom e spostamento) per tutta la sua permanenza sullo schermo.
 * In pausa il movimento si ferma e riprende da dove era rimasto.
 */
@Composable
fun KenBurnsPhoto(
    photo: PhotoItem,
    fitMode: FitMode,
    enabled: Boolean,
    playing: Boolean,
    durationMillis: Int,
    modifier: Modifier = Modifier,
) {
    if (!enabled) {
        PhotoContent(photo, fitMode, modifier)
        return
    }
    val motion = remember(photo.id) { KenBurnsMotion.forPhoto(photo.id) }
    val progress = remember(photo.id) { Animatable(0f) }
    LaunchedEffect(photo.id, playing, durationMillis) {
        if (!playing) return@LaunchedEffect
        val remaining = ((1f - progress.value) * durationMillis).toInt()
        if (remaining > 0) progress.animateTo(1f, tween(remaining, easing = LinearEasing))
    }
    PhotoContent(
        photo,
        fitMode,
        // Il valore dell'animazione si legge solo in fase di disegno: nessuna ricomposizione per frame.
        modifier.clipToBounds().graphicsLayer {
            val value = progress.value
            val scale = motion.scaleAt(value)
            scaleX = scale
            scaleY = scale
            translationX = motion.offsetXAt(value) * size.width
            translationY = motion.offsetYAt(value) * size.height
        },
    )
}
