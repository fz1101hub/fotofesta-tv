package it.fotofesta.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import it.fotofesta.tv.R
import it.fotofesta.tv.guests.PartyHub
import it.fotofesta.tv.media.PartySynth
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PartyGames
import it.fotofesta.tv.model.PartyShows
import it.fotofesta.tv.model.PhotoItem
import kotlinx.coroutines.delay
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Chi non c'è
// ---------------------------------------------------------------------------------------------

/** Invitati attesi (dalla regia) contro chi si è fatto vivo: arrivati, foto, reazioni, giochi. */
@Composable
fun AbsentScreen(roll: PartyGames.Roll, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ShowHeader(stringResource(R.string.show_absent), stringResource(R.string.show_absent_body), onBack)
        if (roll.present.isEmpty() && roll.missing.isEmpty()) {
            Text(stringResource(R.string.absent_empty), style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            NameColumn(quantityStringResource(R.plurals.absent_missing, roll.missing.size), roll.missing, Color(0xFFFFC145), Modifier.weight(1f))
            NameColumn(quantityStringResource(R.plurals.absent_present, roll.present.size), roll.present, Color(0xFF4CD787), Modifier.weight(1f))
        }
    }
}

@Composable
private fun NameColumn(title: String, names: List<String>, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = color)
        names.take(14).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { name ->
                    Text(
                        name,
                        Modifier.weight(1f).background(SurfaceDark, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (names.size > 14) Text("+${names.size - 14}", color = TextSecondary, style = MaterialTheme.typography.titleMedium)
    }
}

// ---------------------------------------------------------------------------------------------
// Colori delle foto (per l'opera d'arte, il QR vivente e la festa che suona)
// ---------------------------------------------------------------------------------------------

@Composable
private fun rememberPhotoColors(photos: List<PhotoItem>, limit: Int): List<Int> {
    val context = LocalContext.current
    val colors = remember { mutableStateListOf<Int>() }
    LaunchedEffect(photos.size) {
        colors.clear()
        photos.filterNot { it.video }.shuffled().take(limit).forEach { photo ->
            loadPixels(context, photo, 8, 8)?.first?.let { colors += PartyShows.average(it) }
        }
    }
    return colors
}

// ---------------------------------------------------------------------------------------------
// L'opera d'arte della festa
// ---------------------------------------------------------------------------------------------

/** Un quadro astratto alla Mondrian, dipinto con i colori medi delle foto della festa. */
@Composable
fun ArtworkScreen(photos: List<PhotoItem>, title: String, onBack: () -> Unit) {
    val colors = rememberPhotoColors(photos, MAX_ART_PHOTOS)
    var seed by remember { mutableIntStateOf(1) }
    val blocks = remember(colors.size, seed) { PartyGames.artwork(colors.toList(), seed.toLong()) }
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ShowHeader(stringResource(R.string.show_artwork), stringResource(R.string.artwork_caption, title, colors.size), onBack)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(1.25f).background(Color(0xFF2A1E12)).padding(16.dp).background(Color(0xFFF4EEDF)).padding(10.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    blocks.forEach { block ->
                        val topLeft = Offset(block.left * size.width, block.top * size.height)
                        val blockSize = Size((block.right - block.left) * size.width, (block.bottom - block.top) * size.height)
                        drawRect(Color(block.color), topLeft, blockSize)
                        drawRect(Color(0xFF111111), topLeft, blockSize, style = Stroke(width = 7f))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.artwork_title), style = MaterialTheme.typography.headlineSmall.copy(fontStyle = FontStyle.Italic), color = TextPrimary)
                Text(stringResource(R.string.artwork_technique, colors.size), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                TvButton(stringResource(R.string.artwork_again), { seed++ })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// La festa suona
// ---------------------------------------------------------------------------------------------

/** Ogni foto diventa una nota: si vedono scorrere mentre suona la melodia della festa. */
@Composable
fun SoundShowScreen(photos: List<PhotoItem>, onBack: () -> Unit) {
    val stills = remember { photos.filterNot { it.video }.shuffled().take(MAX_SOUND_PHOTOS) }
    val context = LocalContext.current
    val colors = remember { mutableStateListOf<Pair<PhotoItem, Int>>() }
    var index by remember { mutableIntStateOf(-1) }
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        stills.forEach { photo -> loadPixels(context, photo, 8, 8)?.first?.let { colors += photo to PartyShows.average(it) } }
        run++
    }
    LaunchedEffect(run) {
        if (run == 0 || colors.isEmpty()) return@LaunchedEffect
        val notes = PartyGames.notes(colors.map { it.second })
        PartySynth.play(notes)
        notes.forEachIndexed { i, note ->
            index = i
            delay(note.millis.toLong())
        }
        index = -1
    }
    DisposableEffect(Unit) { onDispose { PartySynth.stop() } }
    val current = colors.getOrNull(index)
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ShowHeader(stringResource(R.string.show_sound), stringResource(R.string.show_sound_body), onBack)
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (current != null) {
                PhotoContent(current.first, FitMode.CONTAIN, Modifier.fillMaxSize(), contentDescription = null, placeholder = false)
            } else if (colors.isNotEmpty()) {
                TvButton(stringResource(R.string.sound_again), { run++ })
            } else {
                Text(stringResource(R.string.sound_loading), color = TextSecondary, style = MaterialTheme.typography.titleMedium)
            }
        }
        // La melodia come barre colorate: alte le note acute.
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            if (colors.isEmpty()) return@Canvas
            val notes = PartyGames.notes(colors.map { it.second })
            val bar = size.width / colors.size
            val top = notes.maxOf { it.frequency }
            colors.forEachIndexed { i, (_, color) ->
                val height = (notes[i].frequency / top).toFloat() * size.height
                drawRect(Color(color).copy(alpha = if (i == index) 1f else .55f), Offset(i * bar, size.height - height), Size(bar - 2f, height))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// QR vivente
// ---------------------------------------------------------------------------------------------

/**
 * Il QR della festa fatto con i colori delle foto: ogni modulo scuro prende il colore di una foto,
 * scurito quanto basta perché i telefoni lo leggano ancora, e pulsa piano.
 */
@Composable
fun LivingQrScreen(url: String?, photos: List<PhotoItem>, onBack: () -> Unit) {
    val colors = rememberPhotoColors(photos, MAX_ART_PHOTOS)
    val matrix = remember(url) {
        url?.let {
            runCatching {
                MultiFormatWriter().encode(it, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 0))
            }.getOrNull()
        }
    }
    val pulse by rememberInfiniteTransition(label = "qr").animateFloat(0f, 1f, infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ShowHeader(stringResource(R.string.show_living_qr), stringResource(R.string.show_living_qr_body), onBack)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (matrix == null) {
                Text(stringResource(R.string.guests_waiting_network), color = TextSecondary)
                return@Box
            }
            Canvas(Modifier.fillMaxHeight().aspectRatio(1f).background(Color.White).padding(18.dp)) {
                val cell = size.width / matrix.width
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    if (!matrix.get(x, y)) continue
                    val base = if (colors.isEmpty()) 0xFF000000.toInt() else colors[(x * 31 + y * 17) % colors.size]
                    val wave = (sin((x + y) * 0.35 + pulse * 2 * Math.PI) * 0.5 + 0.5).toFloat()
                    drawRect(darkened(base, 0.25f + 0.1f * wave), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/** Colore scurito fino a [maxLuminance]: i moduli del QR devono restare ben più scuri del bianco. */
private fun darkened(color: Int, maxLuminance: Float): Color {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    val scale = if (luminance > maxLuminance) maxLuminance / luminance else 1f
    return Color(r * scale / 255f, g * scale / 255f, b * scale / 255f)
}

// ---------------------------------------------------------------------------------------------
// Il sogno della festa
// ---------------------------------------------------------------------------------------------

/** Foto che si dissolvono una nell'altra, grandi, lente e sfumate, come in un sogno. */
@Composable
fun DreamScreen(photos: List<PhotoItem>, onBack: () -> Unit) {
    val stills = remember { photos.filterNot { it.video }.shuffled() }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(stills.size) {
        while (stills.size > 1) {
            delay(DREAM_STEP_MS)
            index = (index + 1) % stills.size
        }
    }
    val drift by rememberInfiniteTransition(label = "dream").animateFloat(0f, 1f, infiniteRepeatable(tween(DREAM_STEP_MS.toInt() * 2, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Box(Modifier.fillMaxSize().background(Color(0xFF07060F))) {
        if (stills.isEmpty()) {
            ShowHeader(stringResource(R.string.show_dream), stringResource(R.string.slideshow_empty), onBack)
            return@Box
        }
        listOf(index, (index + 1) % stills.size).forEachIndexed { layer, i ->
            PhotoContent(
                stills[i],
                FitMode.CROP,
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (layer == 0) 1f - drift * 0.6f else drift * 0.6f
                    scaleX = 1.08f + drift * 0.06f
                    scaleY = 1.08f + drift * 0.06f
                    translationX = (drift - 0.5f) * 60f * (if (layer == 0) 1 else -1)
                },
                contentDescription = null,
                placeholder = false,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color(0xCC1A0B2E)), radius = 1400f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x338B6CFF), Color(0x33FF4F9A)))))
        Text(
            stringResource(R.string.show_dream),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            style = MaterialTheme.typography.headlineSmall.copy(fontStyle = FontStyle.Italic),
            color = Color.White.copy(alpha = .8f),
        )
        Box(Modifier.align(Alignment.TopEnd).padding(horizontal = 48.dp, vertical = 27.dp)) {
            TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.initialFocus(), ghost = true)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Il museo della festa
// ---------------------------------------------------------------------------------------------

/** Le foto appese in cornici dorate su una parete da museo, con la targhetta di ogni opera. */
@Composable
fun MuseumScreen(photos: List<PhotoItem>, title: String, onBack: () -> Unit) {
    val stills = remember { photos.filterNot { it.video }.take(MAX_MUSEUM) }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(stills.size) {
        while (stills.size > 1) {
            delay(MUSEUM_STEP_MS)
            index = (index + 1) % stills.size
        }
    }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3B2F2A), Color(0xFF231B17)))).padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShowHeader(stringResource(R.string.show_museum), stringResource(R.string.museum_subtitle, title), onBack)
        val photo = stills.getOrNull(index)
        if (photo == null) {
            Text(stringResource(R.string.slideshow_empty), color = TextSecondary)
            return@Column
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).fillMaxHeight(.92f)
                    .border(18.dp, Brush.linearGradient(listOf(Color(0xFFC9A44C), Color(0xFF8A6A22), Color(0xFFE8CF7A))), RoundedCornerShape(4.dp))
                    .padding(18.dp)
                    .background(Color(0xFFF7F2E7))
                    .padding(22.dp),
            ) {
                PhotoContent(photo, FitMode.CONTAIN, Modifier.fillMaxSize(), contentDescription = null, placeholder = false)
            }
            Column(
                Modifier.width(300.dp).background(Color(0xFFF2EBDD), RoundedCornerShape(4.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.museum_work, index + 1), style = MaterialTheme.typography.titleLarge.copy(fontStyle = FontStyle.Italic), color = Color(0xFF231B17))
                Text(photo.sender?.takeIf { it.isNotBlank() } ?: stringResource(R.string.museum_unknown), style = MaterialTheme.typography.titleMedium, color = Color(0xFF3B2F2A))
                Text(stringResource(R.string.museum_technique), style = MaterialTheme.typography.bodySmall, color = Color(0xFF5C4A40))
                Text(stringResource(R.string.museum_collection, title), style = MaterialTheme.typography.bodySmall, color = Color(0xFF5C4A40))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// La festa a capitoli
// ---------------------------------------------------------------------------------------------

/** Le foto degli invitati divise nei capitoli segnati dalla regia, ognuno con il suo titolo. */
@Composable
fun ChaptersScreen(photos: List<PhotoItem>, chapters: List<PartyHub.ChapterMark>, onBack: () -> Unit) {
    val guestPhotos = remember { photos.filter { it.receivedAtMillis != null && !it.video }.sortedBy { it.receivedAtMillis } }
    val sections = remember(chapters) {
        val marks = listOf(PartyHub.ChapterMark("", 0L)) + chapters
        marks.mapIndexed { i, mark ->
            val end = marks.getOrNull(i + 1)?.atMillis ?: Long.MAX_VALUE
            mark to guestPhotos.filter { (it.receivedAtMillis ?: 0L) in mark.atMillis until end }
        }.filter { it.second.isNotEmpty() }
    }
    var section by remember { mutableIntStateOf(0) }
    var photoIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(sections.size) {
        while (sections.isNotEmpty()) {
            sections.forEachIndexed { s, (_, list) ->
                section = s
                photoIndex = -1
                delay(CHAPTER_TITLE_MS)
                list.take(MAX_CHAPTER_PHOTOS).indices.forEach { p ->
                    photoIndex = p
                    delay(CHAPTER_PHOTO_MS)
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val current = sections.getOrNull(section)
        if (current == null) {
            Column(Modifier.padding(horizontal = 48.dp, vertical = 30.dp)) {
                ShowHeader(stringResource(R.string.show_chapters), stringResource(R.string.chapters_empty), onBack)
            }
            return@Box
        }
        val (mark, list) = current
        val photo = list.getOrNull(photoIndex)
        if (photo == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Kicker(stringResource(R.string.chapters_number, section + 1))
                Text(mark.title.ifBlank { stringResource(R.string.chapters_first) }, style = MaterialTheme.typography.displaySmall, color = TextPrimary, textAlign = TextAlign.Center)
                Text(quantityStringResource(R.plurals.awards_photo_count, list.size), color = TextSecondary)
            }
        } else {
            PhotoContent(photo, FitMode.CONTAIN, Modifier.fillMaxSize(), contentDescription = null, placeholder = false)
            Text(
                mark.title.ifBlank { stringResource(R.string.chapters_first) },
                Modifier.align(Alignment.TopStart).padding(36.dp).background(Background.copy(alpha = .7f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        }
        Box(Modifier.align(Alignment.TopEnd).padding(horizontal = 48.dp, vertical = 27.dp)) {
            TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.initialFocus(), ghost = true)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Accadde oggi
// ---------------------------------------------------------------------------------------------

/** Foto della raccolta scattate in questo stesso giorno negli anni passati. */
@Composable
fun OnThisDayScreen(photos: List<Pair<PhotoItem, Long>>, onBack: () -> Unit, loading: Boolean) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(photos.size) {
        while (photos.size > 1) {
            delay(ON_THIS_DAY_STEP_MS)
            index = (index + 1) % photos.size
        }
    }
    val now = remember { System.currentTimeMillis() }
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ShowHeader(stringResource(R.string.show_on_this_day), when {
            loading -> stringResource(R.string.on_this_day_loading)
            photos.isEmpty() -> stringResource(R.string.on_this_day_empty)
            else -> quantityStringResource(R.plurals.awards_photo_count, photos.size)
        }, onBack)
        val current = photos.getOrNull(index % maxOf(photos.size, 1)) ?: return@Column
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
            PhotoContent(current.first, FitMode.CONTAIN, Modifier.fillMaxSize(), contentDescription = null, placeholder = false)
            Text(
                quantityStringResource(R.plurals.on_this_day_years, PartyGames.yearsAgo(current.second, now)),
                Modifier.align(Alignment.BottomStart).padding(24.dp).background(Accent, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                color = OnAccent,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Trova le differenze
// ---------------------------------------------------------------------------------------------

/** La stessa foto due volte: in quella di destra cinque differenze, svelate dopo un minuto. */
@Composable
fun DifferencesScreen(photos: List<PhotoItem>, onBack: () -> Unit) {
    val stills = remember { photos.filterNot { it.video } }
    var round by remember { mutableIntStateOf(0) }
    val photo = remember(round) { stills.randomOrNull() }
    val differences = remember(round) { PartyGames.differences(System.nanoTime()) }
    var revealed by remember(round) { mutableStateOf(false) }
    var left by remember(round) { mutableIntStateOf(DIFFERENCES_SECONDS) }
    LaunchedEffect(round) {
        while (left > 0) {
            delay(1_000)
            left--
        }
        revealed = true
    }
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.games_kicker), stringResource(R.string.show_differences), stringResource(R.string.differences_subtitle, differences.size), modifier = Modifier.weight(1f))
            Text(if (revealed) "" else "$left″", style = MaterialTheme.typography.headlineMedium, color = Accent)
            Spacer(Modifier.width(18.dp))
            TvButton(
                stringResource(if (revealed) R.string.differences_next else R.string.differences_reveal),
                { if (revealed) round++ else revealed = true },
                modifier = Modifier.initialFocus(),
                icon = Icons.Rounded.Visibility,
            )
            Spacer(Modifier.width(12.dp))
            TvButton(stringResource(R.string.action_back), onBack, ghost = true)
        }
        if (photo == null) {
            Text(stringResource(R.string.games_need_photos, 1), color = TextSecondary)
            return@Column
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp))) {
                PhotoContent(photo, FitMode.CROP, Modifier.fillMaxSize(), contentDescription = null, placeholder = false)
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp))) {
                PhotoContent(photo, FitMode.CROP, Modifier.fillMaxSize(), contentDescription = null, placeholder = false)
                Canvas(Modifier.fillMaxSize()) {
                    differences.forEach { diff ->
                        val center = Offset(diff.x * size.width, diff.y * size.height)
                        val radius = diff.radius * size.minDimension
                        when (diff.kind) {
                            PartyGames.DiffKind.DOT -> drawCircle(Color(diff.color), radius * .55f, center)
                            PartyGames.DiffKind.STAR -> {
                                for (k in 0 until 5) {
                                    val angle = Math.toRadians(k * 72.0 - 90).toFloat()
                                    drawLine(Color(diff.color), center, center + Offset(kotlin.math.cos(angle) * radius, sin(angle.toDouble()).toFloat() * radius), strokeWidth = radius / 4)
                                }
                            }
                            PartyGames.DiffKind.MIRROR -> drawRect(Color(diff.color).copy(alpha = .55f), center - Offset(radius, radius / 2), Size(radius * 2, radius))
                            PartyGames.DiffKind.TINT -> drawCircle(Color(diff.color).copy(alpha = .45f), radius, center)
                            PartyGames.DiffKind.HIDE -> drawCircle(Color.Black.copy(alpha = .7f), radius * .7f, center)
                        }
                        if (revealed) drawCircle(Color.White, radius * 1.5f, center, style = Stroke(width = 6f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Quiz con i telefoni: sui festeggiati, chi è da piccolo, indovina la canzone
// ---------------------------------------------------------------------------------------------

/** Domanda del quiz: [image] carica la foto da mostrare, [track] è il brano da far ascoltare. */
data class QuizItem(
    val question: String,
    val choices: List<String>,
    val correct: Int,
    val imageKey: String? = null,
    val track: String? = null,
)

/** Una domanda dopo l'altra: i telefoni rispondono, poi la risposta giusta e la classifica. */
@Composable
fun QuizScreen(
    title: String,
    items: List<QuizItem>,
    emptyText: String,
    phones: PhonePlay?,
    loadImage: (String) -> ByteArray?,
    onPlayTrack: (String) -> Unit,
    onStopTrack: () -> Unit,
    onBack: () -> Unit,
) {
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    val item = items.getOrNull(index)
    LaunchedEffect(index, phones) {
        revealed = false
        if (item == null) return@LaunchedEffect
        phones?.game?.ask(item.question, item.choices, item.correct)
        item.track?.let(onPlayTrack)
    }
    StopPhoneGameOnExit(phones, true)
    DisposableEffect(Unit) { onDispose { onStopTrack() } }
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.games_kicker), title, if (items.isEmpty()) null else stringResource(R.string.quiz_progress, index + 1, items.size), modifier = Modifier.weight(1f))
            if (item != null) {
                TvButton(
                    stringResource(if (!revealed) R.string.quiz_reveal else if (index + 1 < items.size) R.string.quiz_next else R.string.quiz_restart),
                    {
                        if (!revealed) {
                            revealed = true
                            phones?.game?.reveal()
                        } else {
                            index = if (index + 1 < items.size) index + 1 else 0
                        }
                    },
                    modifier = Modifier.initialFocus(),
                )
                Spacer(Modifier.width(12.dp))
            }
            TvButton(stringResource(R.string.action_back), onBack, ghost = true)
        }
        if (item == null) {
            Text(emptyText, style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            return@Column
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.question, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                item.imageKey?.let { key ->
                    val image = rememberPartyImage(key) { loadImage(key) }
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(SurfaceDark), contentAlignment = Alignment.Center) {
                        image?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
                    }
                }
                if (item.track != null) {
                    MusicBars(Modifier.fillMaxWidth().height(90.dp))
                    TvButton(stringResource(R.string.quiz_play_again), { onPlayTrack(item.track) })
                }
                item.choices.forEachIndexed { i, choice -> ChoiceLabel("${'A' + i}  $choice", revealed && i == item.correct) }
            }
            if (phones != null) PhonePlayPanel(phones, revealed, Modifier.width(300.dp))
        }
    }
}

/** Barre che saltano mentre suona il brano da indovinare. */
@Composable
private fun MusicBars(modifier: Modifier) {
    val time by rememberInfiniteTransition(label = "bars").animateFloat(0f, 1f, infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val bars = 24
        val width = size.width / bars
        for (i in 0 until bars) {
            val h = (sin(i * 0.9 + time * 2 * Math.PI) * 0.4 + 0.55).toFloat() * size.height
            drawRect(Brush.verticalGradient(listOf(Color(0xFFFF4F9A), Color(0xFF8B6CFF))), Offset(i * width + 3, size.height - h), Size(width - 6, h))
        }
    }
}

private const val MAX_ART_PHOTOS = 60
private const val MAX_SOUND_PHOTOS = 40
private const val MAX_MUSEUM = 40
private const val MAX_CHAPTER_PHOTOS = 12
private const val DREAM_STEP_MS = 7_000L
private const val MUSEUM_STEP_MS = 9_000L
private const val CHAPTER_TITLE_MS = 3_500L
private const val CHAPTER_PHOTO_MS = 4_000L
private const val ON_THIS_DAY_STEP_MS = 6_000L
private const val DIFFERENCES_SECONDS = 60
