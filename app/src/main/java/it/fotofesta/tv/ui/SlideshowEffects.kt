package it.fotofesta.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoFilter
import it.fotofesta.tv.model.PhotoItem
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------
// Effetti dello slideshow: filtri, zapping, coriandoli, TG, sigla, pila di polaroid, totem
// ---------------------------------------------------------------------------------------------

/**
 * Proiettore: sul muro i neri diventano grigi e i colori si sbiadiscono, quindi contrasto e
 * saturazione salgono un po'. Serve Android 12 (effetti di disegno); prima lo schermo resta com'è.
 */
fun Modifier.projectorBoost(enabled: Boolean): Modifier {
    if (!enabled || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return this
    val effect = projectorEffect()
    return graphicsLayer { renderEffect = effect }
}

@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
private fun projectorEffect(): androidx.compose.ui.graphics.RenderEffect {
    val matrix = android.graphics.ColorMatrix().apply { setSaturation(PROJECTOR_SATURATION) }
    val c = PROJECTOR_CONTRAST
    val t = (1 - c) * 127.5f
    matrix.postConcat(android.graphics.ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    return android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(matrix)).asComposeRenderEffect()
}

private const val PROJECTOR_CONTRAST = 1.18f
private const val PROJECTOR_SATURATION = 1.2f

/** Filtro sulla foto: bianco e nero, seppia, caldo, freddo o anni '70. */
fun Modifier.photoFilter(filter: PhotoFilter): Modifier {
    if (filter == PhotoFilter.NONE) return this
    // Le fusioni "Saturation" e "Color" (Android 10+) cambiano i colori senza coprire la foto;
    // prima si usa una velatura leggera.
    val advanced = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
    return drawWithContent {
        drawContent()
        when (filter) {
            PhotoFilter.BLACK_WHITE ->
                if (advanced) drawRect(Color.Gray, blendMode = BlendMode.Saturation) else drawRect(Color.Gray, alpha = .55f)
            PhotoFilter.SEPIA -> {
                if (advanced) drawRect(Color.Gray, blendMode = BlendMode.Saturation)
                drawRect(Color(0xFF8A6A3A), alpha = if (advanced) .6f else .35f, blendMode = if (advanced) BlendMode.Color else BlendMode.SrcOver)
            }
            PhotoFilter.WARM -> drawRect(Color(0xFFFF9A3C), alpha = .18f, blendMode = if (advanced) BlendMode.Softlight else BlendMode.SrcOver)
            PhotoFilter.COOL -> drawRect(Color(0xFF3C8DFF), alpha = .18f, blendMode = if (advanced) BlendMode.Softlight else BlendMode.SrcOver)
            PhotoFilter.VINTAGE -> {
                drawRect(Color(0xFFE8C98A), alpha = .22f, blendMode = if (advanced) BlendMode.Multiply else BlendMode.SrcOver)
                // Vignettatura: bordi più scuri.
                drawRect(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = .55f),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = size.maxDimension * .72f,
                    ),
                )
            }
            PhotoFilter.NONE -> Unit
        }
    }
}

/** Zapping: mezzo secondo di "neve" e il numero del canale a ogni cambio di foto. */
@Composable
fun BoxScope.ZappingOverlay(key: Any?, channel: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        visible = true
        delay(ZAPPING_MS)
        visible = false
    }
    if (!visible) return
    val frame by produceState(0, key) {
        while (true) {
            delay(60)
            value = value + 1
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val random = Random(frame)
        val cell = 12.dp.toPx()
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                val gray = random.nextFloat()
                drawRect(Color(gray, gray, gray), Offset(x, y), Size(cell, cell))
                x += cell
            }
            y += cell
        }
    }
    Text(
        "CH %02d".format(channel % 100),
        Modifier.align(Alignment.TopEnd).padding(48.dp),
        style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        color = Color(0xFF6CFF6C),
    )
}

private class Confetto(val x: Float, val speed: Float, val drift: Float, val spin: Float, val color: Color, val size: Float)

private val ConfettiColors = listOf(Color(0xFFFF7A59), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFFFFFFF))

/** Coriandoli che cadono per qualche secondo a ogni [trigger] (foto nuova degli invitati, reazione 🎉). */
@Composable
fun BoxScope.ConfettiOverlay(trigger: Int) {
    if (trigger == 0 || LocalReducedMotion.current) return
    val progress = remember(trigger) { Animatable(0f) }
    val pieces = remember(trigger) {
        val random = Random(trigger)
        List(CONFETTI_PIECES) {
            Confetto(random.nextFloat(), .6f + random.nextFloat() * .8f, random.nextFloat() * .2f - .1f, random.nextFloat() * 720f, ConfettiColors[random.nextInt(ConfettiColors.size)], 8f + random.nextFloat() * 10f)
        }
    }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(CONFETTI_MS, easing = LinearEasing)) }
    if (progress.value >= 1f) return
    Canvas(Modifier.fillMaxSize()) {
        val t = progress.value
        pieces.forEach { piece ->
            val y = -40f + (size.height + 80f) * (t * piece.speed * 1.4f)
            if (y > size.height + 40f) return@forEach
            val x = size.width * (piece.x + piece.drift * t)
            val side = piece.size.dp.toPx() / 2
            rotate(piece.spin * t, Offset(x, y)) {
                drawRect(piece.color, Offset(x - side, y - side / 2), Size(side * 2, side))
            }
        }
    }
}

/**
 * TG della festa: logo e ora in alto, titolo della foto nella fascia rossa in basso e le notizie
 * (dediche, numeri della festa) che scorrono.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoxScope.NewsOverlay(title: String, headline: String?, ticker: List<String>) {
    Row(
        Modifier.align(Alignment.TopStart).padding(36.dp)
            .background(Color(0xFFC62828), RoundedCornerShape(8.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.news_logo), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), color = Color.White)
        Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 30.dp)) {
        Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.news_breaking),
                Modifier.background(Color(0xFFC62828)).padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                color = Color.White,
            )
            Text(
                headline ?: title,
                Modifier.background(Color.White).padding(horizontal = 18.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF111111),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (ticker.isNotEmpty()) {
            Text(
                ticker.joinToString("   •   "),
                Modifier.fillMaxWidth().background(Color(0xE6101420)).padding(vertical = 8.dp).basicMarquee(iterations = Int.MAX_VALUE),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/** Sigla d'apertura: il titolo della festa per qualche secondo, quando parte lo slideshow. */
@Composable
fun BoxScope.OpeningTitles(title: String, subtitle: String) {
    var visible by remember { mutableStateOf(true) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(900))
        delay(OPENING_MS)
        alpha.animateTo(0f, tween(900))
        visible = false
    }
    if (!visible) return
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color.Black.copy(alpha = .78f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.opening_presents), style = MaterialTheme.typography.titleLarge, color = Accent)
            Text(
                title,
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Black),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    val scale = .92f + .08f * alpha.value
                    scaleX = scale
                    scaleY = scale
                },
            )
            Text(subtitle, style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        }
    }
}

/** Pila di polaroid: la foto nuova cade sopra le precedenti, ognuna un po' storta. */
@Composable
fun StackSlide(photo: PhotoItem, previous: List<PhotoItem>, filter: PhotoFilter, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFF1B1410)), contentAlignment = Alignment.Center) {
        (previous.take(STACK_DEPTH).asReversed() + photo).forEach { item ->
            key(item.id) {
                val drop = remember { Animatable(if (item.id == photo.id) 0f else 1f) }
                LaunchedEffect(Unit) { drop.animateTo(1f, tween(650)) }
                val angle = ((abs(item.id.hashCode()) % 15) - 7).toFloat()
                Box(
                    Modifier.fillMaxHeight(.78f).aspectRatio(.86f)
                        .graphicsLayer {
                            val scale = 1.35f - .35f * drop.value
                            scaleX = scale
                            scaleY = scale
                            alpha = drop.value
                            translationX = ((abs(item.id.hashCode() / 7) % 120) - 60).dp.toPx()
                        }
                        .rotate(angle)
                        .background(Color(0xFFF8F5EE), RoundedCornerShape(4.dp))
                        .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 70.dp),
                ) {
                    PhotoContent(item, FitMode.CROP, Modifier.fillMaxSize().photoFilter(filter), placeholder = false)
                }
            }
        }
    }
}

/**
 * TV in verticale (totem): il contenuto si misura con larghezza e altezza scambiate e ruota di 90°,
 * così riempie lo schermo girato.
 */
@Composable
fun PortraitFrame(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeable = measurables.first().measure(Constraints.fixed(height, width))
        layout(width, height) {
            placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) { rotationZ = 90f }
        }
    }
}

/** Data di oggi per la sigla ("sabato 24 ottobre 2026"). */
@Composable
fun rememberTodayText(): String = remember {
    java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL).format(java.util.Date())
}

private const val ZAPPING_MS = 450L
private const val CONFETTI_MS = 3_200
private const val CONFETTI_PIECES = 90
private const val OPENING_MS = 3_500L
private const val STACK_DEPTH = 3
