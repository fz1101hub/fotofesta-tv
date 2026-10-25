package it.fotofesta.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import it.fotofesta.tv.model.PhotoItem
import kotlin.math.roundToInt

/** Da questo rapporto larghezza/altezza in su la foto è una panoramica e scorre. */
const val PANORAMA_ASPECT = 2.2f

/** Oltre questo rapporto la panoramica si taglia ai lati: resta leggera anche su una TV modesta. */
private const val MAX_PANORAMA_ASPECT = 4f

/**
 * Panoramica a tutta altezza che scorre da sinistra a destra durante la permanenza della foto,
 * invece di restare una striscia sottile al centro. Si decodifica all'altezza dello schermo; compare
 * in dissolvenza sopra la foto intera quando è pronta, e solo allora comincia a muoversi.
 */
@Composable
fun PanoramaPhoto(photo: PhotoItem, aspect: Float, playing: Boolean, durationMillis: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    BoxWithConstraints(modifier.clipToBounds()) {
        val height = constraints.maxHeight
        val width = (height * aspect.coerceAtMost(MAX_PANORAMA_ASPECT)).roundToInt()
        val travel = (width - constraints.maxWidth).coerceAtLeast(0).toFloat()
        val request = remember(photo.id, width, height) {
            ImageRequest.Builder(context)
                .data(photo.ref.coilModel())
                .size(width, height)
                .precision(Precision.EXACT)
                .memoryCacheKey("panorama:${photo.id}:$height")
                .build()
        }
        var loaded by remember(photo.id) { mutableStateOf(false) }
        val alpha by animateFloatAsState(if (loaded) 1f else 0f, tween(600), label = "panoramaAlpha")
        val progress = remember(photo.id) { Animatable(0f) }
        LaunchedEffect(photo.id, playing, durationMillis, loaded) {
            if (!loaded || !playing || progress.value >= 1f) return@LaunchedEffect
            progress.animateTo(1f, tween(durationMillis.coerceAtLeast(1), easing = FastOutSlowInEasing))
        }
        val widthDp = with(LocalDensity.current) { width.toDp() }
        AsyncImage(
            model = request,
            contentDescription = photo.title,
            contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
            // Più larga dello schermo e centrata: lo spostamento va dal bordo sinistro al destro.
            modifier = Modifier.requiredSize(widthDp, maxHeight).graphicsLayer {
                this.alpha = alpha
                translationX = travel / 2f - travel * progress.value
            },
        )
    }
}
