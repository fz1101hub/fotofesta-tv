package it.fotofesta.tv.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.guests.PartySetup
import it.fotofesta.tv.model.FrameStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Colore dell'evento scelto dalla regia ("#ff4f9a"), o l'arancione dell'app. */
fun PartySetup.Brand.accentColor(): Color =
    accent?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Accent

/** Immagine letta fuori dal thread principale (logo, sponsor); [key] la fa rileggere quando cambia. */
@Composable
fun rememberPartyImage(key: Any?, load: () -> ByteArray?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = load() ?: return@runCatching null
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return image
}

/**
 * Cornice dell'evento sopra lo slideshow: una fascia in basso con il nome e il logo, oppure un
 * bordo nei colori della festa con il nome in un'etichetta.
 */
@Composable
fun BoxScope.EventFrame(style: FrameStyle, brand: PartySetup.Brand, logo: ImageBitmap?) {
    if (style == FrameStyle.NONE) return
    val accent = brand.accentColor()
    val name = brand.name
    when (style) {
        FrameStyle.BORDER -> {
            Box(
                Modifier.fillMaxSize().border(
                    14.dp,
                    Brush.linearGradient(listOf(accent, Color(0xFFFF4F9A), Color(0xFF8B6CFF))),
                    RectangleShape,
                ),
            )
            if (name.isNotBlank() || logo != null) {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 14.dp)
                        .background(accent, RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                        .padding(horizontal = 22.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    logo?.let { Image(it, null, Modifier.height(28.dp), contentScale = ContentScale.Fit) }
                    if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.titleMedium, color = OnAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        FrameStyle.BAND -> {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .75f), Color.Transparent)))
                    .padding(horizontal = 48.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            ) {
                logo?.let { Image(it, null, Modifier.height(44.dp), contentScale = ContentScale.Fit) }
                if (name.isNotBlank()) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp).background(accent))
        }
        FrameStyle.NONE -> Unit
    }
}

/**
 * Sponsor tra le foto: ogni [every] cambi di foto uno sponsor a tutto schermo per qualche secondo,
 * a turno. [load] dà l'immagine dello sponsor con quell'identificativo.
 */
@Composable
fun BoxScope.SponsorOverlay(slideTick: Int, every: Int, sponsors: List<String>, load: (String) -> ByteArray?) {
    if (every <= 0 || sponsors.isEmpty()) return
    var shownAt by remember { mutableLongStateOf(0L) }
    val turn = slideTick / every
    val id = sponsors[turn % sponsors.size]
    val image = rememberPartyImage(id) { load(id) }
    LaunchedEffect(turn) {
        if (slideTick == 0 || slideTick % every != 0) return@LaunchedEffect
        shownAt = System.currentTimeMillis()
        delay(SPONSOR_MS)
        shownAt = 0L
    }
    AnimatedVisibility(
        visible = shownAt > 0L && image != null,
        enter = fadeIn(tween(500)),
        exit = fadeOut(tween(500)),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            image?.let { Image(it, null, Modifier.fillMaxSize().padding(80.dp), contentScale = ContentScale.Fit) }
            Text(
                stringResource(R.string.sponsor_thanks),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
            )
        }
    }
}

/** Il prossimo momento della scaletta, se manca meno di mezz'ora. */
@Composable
fun BoxScope.ScheduleBadge(schedule: List<PartySetup.Slot>, modifier: Modifier = Modifier) {
    if (schedule.isEmpty()) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val calendar = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    val next = schedule.firstOrNull { it.minuteOfDay >= minute && it.minuteOfDay - minute <= SCHEDULE_AHEAD_MINUTES } ?: return
    Column(
        modifier.widthIn(max = 420.dp).background(Background.copy(alpha = .78f), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Kicker(stringResource(if (next.minuteOfDay == minute) R.string.schedule_now else R.string.schedule_soon))
        Text("${next.time} · ${next.title}", style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private const val SPONSOR_MS = 6_000L
private const val SCHEDULE_AHEAD_MINUTES = 30
