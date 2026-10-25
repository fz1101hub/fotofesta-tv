package it.fotofesta.tv.ui

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Museum
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import it.fotofesta.tv.R
import it.fotofesta.tv.model.Destination
import it.fotofesta.tv.guests.PartyHub
import it.fotofesta.tv.guests.PartyPlay
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PartyShows
import it.fotofesta.tv.model.PhotoItem
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------
// Spettacoli sullo schermo: riavvolgi la festa, codice a barre, fotomosaico, titoli di coda,
// Televideo, cielo della festa e la festa in numeri
// ---------------------------------------------------------------------------------------------

private class ShowTile(val title: Int, val body: Int, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun ShowsScreen(
    onRewind: () -> Unit,
    onBarcode: () -> Unit,
    onMosaic: () -> Unit,
    onCredits: () -> Unit,
    onTeletext: () -> Unit,
    onSky: () -> Unit,
    onStats: () -> Unit,
    /** Spettacoli dell'ondata 11: chi non c'è, opera d'arte, la festa suona, QR vivente, sogno, museo, capitoli, accadde oggi. */
    onMore: (Destination) -> Unit = {},
    enoughPhotos: Boolean,
    onBack: () -> Unit,
) {
    val tiles = listOf(
        ShowTile(R.string.show_rewind, R.string.show_rewind_body, Icons.Rounded.FastRewind, onRewind),
        ShowTile(R.string.show_barcode, R.string.show_barcode_body, Icons.Rounded.ViewWeek, onBarcode),
        ShowTile(R.string.show_mosaic, R.string.show_mosaic_body, Icons.Rounded.GridOn, onMosaic),
        ShowTile(R.string.show_credits, R.string.show_credits_body, Icons.Rounded.Movie, onCredits),
        ShowTile(R.string.show_teletext, R.string.show_teletext_body, Icons.Rounded.Tv, onTeletext),
        ShowTile(R.string.show_sky, R.string.show_sky_body, Icons.Rounded.AutoAwesome, onSky),
        ShowTile(R.string.show_stats, R.string.show_stats_body, Icons.Rounded.BarChart, onStats),
        ShowTile(R.string.show_absent, R.string.show_absent_body, Icons.Rounded.PersonSearch) { onMore(Destination.ABSENT) },
        ShowTile(R.string.show_artwork, R.string.show_artwork_body, Icons.Rounded.Palette) { onMore(Destination.ARTWORK) },
        ShowTile(R.string.show_sound, R.string.show_sound_body, Icons.Rounded.MusicNote) { onMore(Destination.SOUND_SHOW) },
        ShowTile(R.string.show_living_qr, R.string.show_living_qr_body, Icons.Rounded.QrCode2) { onMore(Destination.LIVING_QR) },
        ShowTile(R.string.show_dream, R.string.show_dream_body, Icons.Rounded.Cloud) { onMore(Destination.DREAM) },
        ShowTile(R.string.show_museum, R.string.show_museum_body, Icons.Rounded.Museum) { onMore(Destination.MUSEUM) },
        ShowTile(R.string.show_chapters, R.string.show_chapters_body, Icons.AutoMirrored.Rounded.MenuBook) { onMore(Destination.CHAPTERS) },
        ShowTile(R.string.show_on_this_day, R.string.show_on_this_day_body, Icons.Rounded.History) { onMore(Destination.ON_THIS_DAY) },
    )
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.shows_kicker), stringResource(R.string.games_shows_title), stringResource(R.string.games_shows_body), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(6.dp),
        ) {
            itemsIndexed(tiles) { index, tile ->
                // Le foto servono a tutti tranne Televideo, cielo e numeri.
                val enabled = enoughPhotos || tile.title in setOf(
                    R.string.show_teletext, R.string.show_sky, R.string.show_stats, R.string.show_credits,
                    R.string.show_absent, R.string.show_living_qr, R.string.show_on_this_day,
                )
                FocusSurface(onClick = tile.onClick, modifier = Modifier.height(130.dp).then(if (index == 0) Modifier.initialFocus() else Modifier), enabled = enabled) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(tile.icon, contentDescription = null, tint = if (it) OnFocus else Accent, modifier = Modifier.size(30.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(tile.title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(tile.body), style = MaterialTheme.typography.bodySmall, color = if (it) OnFocus else TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Header comune degli spettacoli: titolo, sottotitolo e Indietro. */
@Composable
internal fun ShowHeader(title: String, subtitle: String?, onBack: () -> Unit, focusBack: Boolean = true) {
    Row(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.shows_kicker), title, subtitle, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        TvButton(stringResource(R.string.action_back), onBack, modifier = if (focusBack) Modifier.initialFocus() else Modifier, icon = Icons.AutoMirrored.Rounded.ArrowBack)
    }
}

// ---------------------------------------------------------------------------------------------
// Riavvolgi la festa
// ---------------------------------------------------------------------------------------------

/** La festa al contrario, veloce, come una videocassetta riavvolta: dall'ultima foto alla prima. */
@Composable
fun RewindScreen(photos: List<PhotoItem>, onBack: () -> Unit) {
    val ordered = remember { photos.filterNot { it.video }.sortedByDescending { it.receivedAtMillis ?: 0L } }
    var index by remember { mutableIntStateOf(0) }
    var run by remember { mutableIntStateOf(0) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(run) {
        index = 0
        while (index < ordered.size - 1) {
            delay(if (reduced) 1_000 else if (ordered.size > 60) 220 else 420)
            index++
        }
    }
    val done = index >= ordered.size - 1
    val jitter by produceState(0f, run) {
        while (!reduced) {
            value = Random.nextFloat() * 6f - 3f
            delay(90)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ordered.getOrNull(index)?.let { photo ->
            PhotoContent(photo, FitMode.CONTAIN, Modifier.fillMaxSize().graphicsLayer { translationX = jitter }, placeholder = false)
            val time = photo.receivedAtMillis?.let { rememberClock(it) }
            if (time != null) VhsText(time, Modifier.align(Alignment.BottomEnd).padding(48.dp))
        }
        // Righe di scansione e la scritta della videocassetta.
        Canvas(Modifier.fillMaxSize()) {
            var y = 0f
            while (y < size.height) {
                drawRect(Color.Black.copy(alpha = .18f), Offset(0f, y), Size(size.width, 2f))
                y += 6f
            }
        }
        VhsText(if (done) "■ " + stringResource(R.string.rewind_end) else "◀◀ REW", Modifier.align(Alignment.TopStart).padding(48.dp))
        Row(Modifier.align(Alignment.BottomStart).padding(48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
            TvButton(stringResource(R.string.rewind_again), { run++ }, modifier = Modifier.initialFocus(), icon = Icons.Rounded.Replay)
        }
    }
}

@Composable
private fun VhsText(text: String, modifier: Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        color = Color.White,
    )
}

@Composable
private fun rememberClock(epochMillis: Long): String = remember(epochMillis) {
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(epochMillis))
}

// ---------------------------------------------------------------------------------------------
// Codice a barre e fotomosaico: colori medi delle foto
// ---------------------------------------------------------------------------------------------

/** Pixel della foto ridotta a [width]×[height], per i colori medi; null se non si carica. */
internal suspend fun loadPixels(context: Context, photo: PhotoItem, width: Int, height: Int): Pair<IntArray, android.graphics.Bitmap>? {
    val request = ImageRequest.Builder(context)
        .data(photo.ref.coilModel())
        .size(width, height)
        .precision(Precision.EXACT)
        .scale(Scale.FILL)
        .allowHardware(false)
        .memoryCacheKey("pixels:${photo.id}:${width}x$height")
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    val bitmap = result.drawable.toBitmap(width, height)
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    return pixels to bitmap
}

/** Il codice a barre della festa: una striscia per ogni foto, del suo colore medio, in ordine. */
@Composable
fun BarcodeScreen(photos: List<PhotoItem>, onBack: () -> Unit) {
    val context = LocalContext.current
    val ordered = remember { photos.filterNot { it.video }.sortedBy { it.receivedAtMillis ?: 0L }.take(MAX_BARCODE) }
    val colors = remember { mutableStateListOf<Color>() }
    LaunchedEffect(Unit) {
        ordered.forEach { photo ->
            val pixels = loadPixels(context, photo, 8, 8)?.first ?: return@forEach
            colors += Color(PartyShows.average(pixels))
        }
    }
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ShowHeader(
            stringResource(R.string.show_barcode),
            if (colors.size < ordered.size) stringResource(R.string.barcode_loading, colors.size, ordered.size) else quantityStringResource(R.plurals.barcode_done, colors.size),
            onBack,
        )
        Canvas(Modifier.fillMaxWidth().weight(1f).background(Color.Black, RoundedCornerShape(16.dp))) {
            if (colors.isEmpty()) return@Canvas
            val stripe = size.width / colors.size
            colors.forEachIndexed { index, color -> drawRect(color, Offset(index * stripe, 0f), Size(stripe + 1f, size.height)) }
        }
    }
}

/**
 * Fotomosaico: una foto grande fatta di tante piccole foto della festa, ognuna messa dove il suo
 * colore medio somiglia di più a quel punto della foto grande.
 */
@Composable
fun PhotoMosaicScreen(photos: List<PhotoItem>, onBack: () -> Unit) {
    val context = LocalContext.current
    val stills = remember { photos.filterNot { it.video } }
    var target by remember { mutableStateOf(stills.randomOrNull()) }
    var cells by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    var loaded by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(target) {
        val big = target ?: return@LaunchedEffect
        cells = null
        loaded = 0
        val pool = stills.shuffled().take(MAX_MOSAIC_TILES)
        val tiles = ArrayList<Pair<Int, ImageBitmap>>()
        pool.forEach { photo ->
            loadPixels(context, photo, TILE_PX, TILE_PX)?.let { (pixels, bitmap) -> tiles += PartyShows.average(pixels) to bitmap.asImageBitmap() }
            loaded++
        }
        val grid = loadPixels(context, big, MOSAIC_COLUMNS, MOSAIC_ROWS)?.first ?: return@LaunchedEffect
        if (tiles.isEmpty()) return@LaunchedEffect
        val choice = PartyShows.mosaic(grid.toList(), tiles.map { it.first })
        cells = choice.map { tiles[it].second }
    }
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ShowHeader(
            stringResource(R.string.show_mosaic),
            if (cells == null) stringResource(R.string.mosaic_loading, loaded, minOf(stills.size, MAX_MOSAIC_TILES)) else stringResource(R.string.show_mosaic_body),
            onBack,
            focusBack = false,
        )
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val current = cells
            if (current != null) {
                Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width / MOSAIC_COLUMNS
                        val h = size.height / MOSAIC_ROWS
                        current.forEachIndexed { index, image ->
                            val x = (index % MOSAIC_COLUMNS) * w
                            val y = (index / MOSAIC_COLUMNS) * h
                            drawImage(
                                image,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(image.width, image.height),
                                dstOffset = IntOffset(x.toInt(), y.toInt()),
                                dstSize = IntSize((w + 1).toInt(), (h + 1).toInt()),
                            )
                        }
                    }
                    // Un velo della foto grande aiuta a riconoscerla da lontano.
                    if (hint) target?.let { PhotoContent(it, FitMode.CROP, Modifier.fillMaxSize().graphicsLayer { alpha = .3f }, placeholder = false) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(stringResource(R.string.mosaic_new), { target = stills.randomOrNull() }, modifier = Modifier.initialFocus(), icon = Icons.Rounded.Replay)
            TvButton(stringResource(if (hint) R.string.mosaic_hint_off else R.string.mosaic_hint_on), { hint = !hint })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Titoli di coda
// ---------------------------------------------------------------------------------------------

/** Titoli di coda della festa, che scorrono come al cinema. */
@Composable
fun CreditsScreen(title: String, sections: List<PartyShows.CreditSection>, music: String?, onBack: () -> Unit) {
    val scroll = rememberScrollState()
    val reduced = LocalReducedMotion.current
    LaunchedEffect(sections, reduced) {
        delay(600)
        while (!reduced) {
            scroll.scrollTo(0)
            delay(1_500)
            val distance = scroll.maxValue
            scroll.animateScrollTo(distance, tween((distance * 9).coerceAtLeast(4_000), easing = LinearEasing))
            delay(4_000)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(46.dp),
        ) {
            Text(title, style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black), color = Color.White, textAlign = TextAlign.Center)
            sections.forEach { section ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(creditTitle(section.kind)).uppercase(), style = MaterialTheme.typography.titleMedium, color = Accent, letterSpacing = 3.sp)
                    section.names.forEach { Text(it, style = MaterialTheme.typography.headlineSmall, color = Color.White) }
                }
            }
            if (music != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.credits_music).uppercase(), style = MaterialTheme.typography.titleMedium, color = Accent, letterSpacing = 3.sp)
                    Text(music, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                }
            }
            Text(stringResource(R.string.credits_directed), style = MaterialTheme.typography.titleLarge, color = TextSecondary)
            Text(stringResource(R.string.credits_thanks), style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
        }
        TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.align(Alignment.TopEnd).padding(36.dp).initialFocus(), icon = Icons.AutoMirrored.Rounded.ArrowBack, ghost = true)
    }
}

private fun creditTitle(kind: PartyShows.CreditKind): Int = when (kind) {
    PartyShows.CreditKind.PHOTOGRAPHERS -> R.string.credits_photographers
    PartyShows.CreditKind.MESSAGES -> R.string.credits_messages
    PartyShows.CreditKind.CHAMPIONS -> R.string.credits_champions
    PartyShows.CreditKind.GUESTS -> R.string.credits_guests
}

// ---------------------------------------------------------------------------------------------
// Televideo
// ---------------------------------------------------------------------------------------------

private val TeletextBlue = Color(0xFF0000FF)
private val TeletextYellow = Color(0xFFFFFF00)
private val TeletextCyan = Color(0xFF00FFFF)
private val TeletextGreen = Color(0xFF00FF00)
private val TeletextRed = Color(0xFFFF0000)
private val TeletextMagenta = Color(0xFFFF00FF)

/** Pagina 888 del Televideo della festa: notizie, classifica e numeri, con le sottopagine che girano. */
@Composable
fun TeletextScreen(title: String, messages: List<PartyHub.Message>, coins: List<PartyPlay.Coins>, stats: PartyShows.Stats, onBack: () -> Unit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val page = ((now / 8_000) % 3).toInt()
    val header = remember(now / 1_000) {
        java.text.SimpleDateFormat("EEE dd MMM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now)).uppercase()
    }
    val mono = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    Box(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 60.dp, vertical = 40.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("P888", style = mono, color = Color.White)
                Spacer(Modifier.width(24.dp))
                Text(title.uppercase(), style = mono, color = TeletextYellow, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(header, style = mono, color = Color.White)
            }
            Text(
                " " + stringResource(listOf(R.string.teletext_news, R.string.teletext_ranking, R.string.teletext_numbers)[page]).uppercase() + " ",
                Modifier.fillMaxWidth().background(TeletextBlue).padding(vertical = 6.dp),
                style = mono.copy(fontSize = 40.sp),
                color = TeletextYellow,
            )
            when (page) {
                0 -> if (messages.isEmpty()) Text(stringResource(R.string.teletext_no_news), style = mono, color = TeletextCyan)
                else messages.takeLast(8).asReversed().forEachIndexed { index, message ->
                    Text(
                        (message.name?.uppercase()?.let { "$it: " } ?: "") + message.text,
                        style = mono,
                        color = if (index % 2 == 0) TeletextCyan else Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                1 -> if (coins.isEmpty()) Text(stringResource(R.string.coins_empty), style = mono, color = TeletextCyan)
                else coins.take(9).forEachIndexed { index, entry ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${index + 1}.", style = mono, color = TeletextYellow, modifier = Modifier.width(80.dp))
                        Text(entry.name.uppercase(), style = mono, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.coins.toString(), style = mono, color = TeletextGreen)
                    }
                }
                else -> {
                    TeletextLine(stringResource(R.string.stats_photos), stats.guestPhotos.toString(), TeletextGreen, mono)
                    TeletextLine(stringResource(R.string.stats_photographers), stats.photographers.toString(), TeletextCyan, mono)
                    TeletextLine(stringResource(R.string.stats_messages), stats.messages.toString(), TeletextMagenta, mono)
                    TeletextLine(stringResource(R.string.stats_reactions), stats.reactions.toString(), TeletextYellow, mono)
                    stats.busiestHour?.let { TeletextLine(stringResource(R.string.stats_busiest), "%02d:00".format(it), TeletextRed, mono) }
                    TeletextLine(stringResource(R.string.teletext_weather), stringResource(R.string.teletext_weather_value), TeletextYellow, mono)
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf(TeletextRed, TeletextGreen, TeletextYellow, TeletextCyan).forEach { color ->
                Box(Modifier.padding(end = 12.dp).size(width = 120.dp, height = 28.dp).background(color))
            }
            Spacer(Modifier.weight(1f))
            Text("${page + 1}/3", style = mono, color = Color.White)
            Spacer(Modifier.width(24.dp))
            TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.initialFocus(), icon = Icons.AutoMirrored.Rounded.ArrowBack, ghost = true)
        }
    }
}

@Composable
private fun TeletextLine(label: String, value: String, color: Color, style: androidx.compose.ui.text.TextStyle) {
    Row(Modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = style, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = style, color = color)
    }
}

// ---------------------------------------------------------------------------------------------
// Cielo della festa
// ---------------------------------------------------------------------------------------------

/**
 * Cielo della festa (anche da proiettare sul soffitto): ogni invitato è una stella con il suo nome,
 * unite in una costellazione nell'ordine di arrivo; a ogni foto nuova passa una stella cadente.
 */
@Composable
fun SkyScreen(names: List<String>, photos: Int, onBack: () -> Unit) {
    val twinkle = rememberInfiniteTransition(label = "sky")
    val phase by twinkle.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart), label = "twinkle")
    val background = remember { List(220) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    var shooting by remember { mutableIntStateOf(0) }
    val shot = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(photos) {
        if (photos == 0) return@LaunchedEffect
        shooting++
        shot.snapTo(0f)
        shot.animateTo(1f, tween(1_400, easing = LinearEasing))
    }
    val reduced = LocalReducedMotion.current
    Box(Modifier.fillMaxSize().background(Color(0xFF02030A))) {
        Canvas(Modifier.fillMaxSize()) {
            background.forEach { (x, y, seed) ->
                val alpha = if (reduced) .6f else .35f + .45f * (0.5f + 0.5f * sin(phase + seed * 12f))
                drawCircle(Color.White.copy(alpha = alpha), radius = 1.2f + seed * 1.6f, center = Offset(x * size.width, y * size.height))
            }
            val points = names.map { starPosition(it, size.width, size.height) }
            points.zipWithNext().forEach { (a, b) -> drawLine(Color(0x55A0C4FF), a, b, strokeWidth = 2f) }
            points.forEach { drawCircle(Color(0xFFFFF4C2), radius = 7f, center = it) }
            if (!reduced && shot.value < 1f) {
                val random = Random(shooting)
                val start = Offset(size.width * (.1f + random.nextFloat() * .5f), size.height * random.nextFloat() * .4f)
                val head = start + Offset(size.width * .35f * shot.value, size.height * .25f * shot.value)
                drawLine(Color.White.copy(alpha = 1f - shot.value), start + (head - start) * .6f, head, strokeWidth = 4f)
            }
        }
        names.forEach { name ->
            Text(
                name,
                Modifier.graphicsLayer {
                    val position = starPosition(name, size.width, size.height)
                    translationX = position.x + 14f
                    translationY = position.y - 12f
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFDDE6FF),
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.show_sky), style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(quantityStringResource(R.plurals.sky_stars, names.size), style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        }
        TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.align(Alignment.TopEnd).padding(36.dp).initialFocus(), icon = Icons.AutoMirrored.Rounded.ArrowBack, ghost = true)
    }
}

/** Posizione fissa della stella di un invitato, calcolata dal nome (resta uguale a ogni apertura). */
private fun starPosition(name: String, width: Float, height: Float): Offset {
    val hash = abs(name.lowercase().hashCode())
    val x = .08f + (hash % 1000) / 1000f * .84f
    val y = .1f + ((hash / 1000) % 1000) / 1000f * .7f
    return Offset(x * width, y * height)
}

// ---------------------------------------------------------------------------------------------
// La festa in numeri
// ---------------------------------------------------------------------------------------------

@Composable
fun StatsScreen(stats: PartyShows.Stats, favorite: PhotoItem?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        ShowHeader(stringResource(R.string.show_stats), stringResource(R.string.show_stats_body), onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatCard(stats.guestPhotos.toString(), stringResource(R.string.stats_photos), Modifier.weight(1f))
            StatCard(stats.photographers.toString(), stringResource(R.string.stats_photographers), Modifier.weight(1f))
            StatCard(stats.messages.toString(), stringResource(R.string.stats_messages), Modifier.weight(1f))
            StatCard(
                stats.reactions.toString() + (stats.topReaction?.let { " " + PartyHub.Reaction.entries[it].emoji } ?: ""),
                stringResource(R.string.stats_reactions),
                Modifier.weight(1f),
            )
            StatCard(stats.coins.toString(), stringResource(R.string.stats_coins), Modifier.weight(1f))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().background(SurfaceDark, RoundedCornerShape(18.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stats.busiestHour?.let { stringResource(R.string.stats_busiest_value, "%02d:00".format(it)) } ?: stringResource(R.string.stats_no_photos),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                // Ore con foto, dalla prima all'ultima.
                val first = stats.hourly.indexOfFirst { it > 0 }
                val last = stats.hourly.indexOfLast { it > 0 }
                if (first >= 0) {
                    val hours = (first..last).toList()
                    val max = hours.maxOf { stats.hourly[it] }.coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                        hours.forEach { hour ->
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.fillMaxWidth().fillMaxHeight(stats.hourly[hour].toFloat() / max * .85f).background(if (hour == stats.busiestHour) Accent else SurfaceRaised, RoundedCornerShape(6.dp)))
                                Text("%02d".format(hour), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                    }
                }
            }
            if (favorite != null) {
                Column(Modifier.width(520.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kicker(stringResource(R.string.stats_favorite))
                    PhotoContent(favorite, FitMode.CROP, Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(18.dp)))
                }
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier) {
    Column(modifier.background(SurfaceDark, RoundedCornerShape(18.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black), color = Accent, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private const val MAX_BARCODE = 600
private const val MAX_MOSAIC_TILES = 120
private const val TILE_PX = 64
private const val MOSAIC_COLUMNS = 40
private const val MOSAIC_ROWS = 22
