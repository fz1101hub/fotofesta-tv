package it.fotofesta.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WineBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.guests.PartyPlay
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PlayUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Giochi con i telefoni: menu, gioco in corso, torneo, scommesse, brindisi e monete
// ---------------------------------------------------------------------------------------------

/** Colori dei disegni, nello stesso ordine della tavolozza della pagina dei telefoni. */
private val DrawingColors = listOf(
    Color(0xFF111111), Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835),
    Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFFFFFFF),
)

private fun kindTitle(kind: PartyPlay.Kind): Int = when (kind) {
    PartyPlay.Kind.DRAW_GUESS -> R.string.play_title_draw_guess
    PartyPlay.Kind.DRAWING_WALL -> R.string.play_title_wall
    PartyPlay.Kind.STORY -> R.string.play_title_story
    PartyPlay.Kind.WHISPER -> R.string.play_title_whisper
    PartyPlay.Kind.COMPLIMENTS -> R.string.play_title_compliments
    PartyPlay.Kind.MISSIONS -> R.string.play_title_missions
    PartyPlay.Kind.PAPARAZZI -> R.string.play_title_paparazzi
    PartyPlay.Kind.SAME_MOMENT -> R.string.play_title_moment
    PartyPlay.Kind.RECREATE -> R.string.play_title_recreate
    PartyPlay.Kind.MOLE -> R.string.play_title_mole
    PartyPlay.Kind.CAPSULE -> R.string.play_title_capsule
}

private fun kindBody(kind: PartyPlay.Kind): Int = when (kind) {
    PartyPlay.Kind.DRAW_GUESS -> R.string.play_body_draw_guess
    PartyPlay.Kind.DRAWING_WALL -> R.string.play_body_wall
    PartyPlay.Kind.STORY -> R.string.play_body_story
    PartyPlay.Kind.WHISPER -> R.string.play_body_whisper
    PartyPlay.Kind.COMPLIMENTS -> R.string.play_body_compliments
    PartyPlay.Kind.MISSIONS -> R.string.play_body_missions
    PartyPlay.Kind.PAPARAZZI -> R.string.play_body_paparazzi
    PartyPlay.Kind.SAME_MOMENT -> R.string.play_body_moment
    PartyPlay.Kind.RECREATE -> R.string.play_body_recreate
    PartyPlay.Kind.MOLE -> R.string.play_body_mole
    PartyPlay.Kind.CAPSULE -> R.string.play_body_capsule
}

private fun kindIcon(kind: PartyPlay.Kind): ImageVector = when (kind) {
    PartyPlay.Kind.DRAW_GUESS -> Icons.Rounded.Brush
    PartyPlay.Kind.DRAWING_WALL -> Icons.Rounded.Palette
    PartyPlay.Kind.STORY -> Icons.Rounded.AutoStories
    PartyPlay.Kind.WHISPER -> Icons.Rounded.RecordVoiceOver
    PartyPlay.Kind.COMPLIMENTS -> Icons.Rounded.Favorite
    PartyPlay.Kind.MISSIONS -> Icons.Rounded.Flag
    PartyPlay.Kind.PAPARAZZI -> Icons.Rounded.CameraAlt
    PartyPlay.Kind.SAME_MOMENT -> Icons.Rounded.Timer
    PartyPlay.Kind.RECREATE -> Icons.Rounded.Replay
    PartyPlay.Kind.MOLE -> Icons.Rounded.Search
    PartyPlay.Kind.CAPSULE -> Icons.Rounded.HourglassTop
}

private class PlayTile(val title: Int, val body: Int, val icon: ImageVector, val onClick: () -> Unit)

/** Tutti i giochi con i telefoni, in una griglia; in fondo i telefoni come luci. */
@Composable
fun PartyGamesScreen(
    partyActive: Boolean,
    play: PlayUiState,
    onStart: (PartyPlay.Kind) -> Boolean,
    onTournament: () -> Unit,
    onBets: () -> Unit,
    onToast: () -> Unit,
    onCoins: () -> Unit,
    onTreasure: () -> Unit,
    onLights: (PartyPlay.Lights?) -> Unit,
    onBack: () -> Unit,
) {
    val tiles = PartyPlay.Kind.entries.map { kind -> PlayTile(kindTitle(kind), kindBody(kind), kindIcon(kind)) { onStart(kind) } } + listOf(
        PlayTile(R.string.play_title_tournament, R.string.play_body_tournament, Icons.Rounded.EmojiEvents, onTournament),
        PlayTile(R.string.play_title_bets, R.string.play_body_bets, Icons.Rounded.Payments, onBets),
        PlayTile(R.string.play_title_toast, R.string.play_body_toast, Icons.Rounded.WineBar, onToast),
        PlayTile(R.string.play_title_treasure, R.string.play_body_treasure, Icons.Rounded.QrCode2, onTreasure),
        PlayTile(R.string.play_title_coins, R.string.play_body_coins, Icons.Rounded.Savings, onCoins),
    )
    Box(Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth()) {
                SectionTitle(
                    stringResource(R.string.party_games_kicker),
                    stringResource(R.string.games_party_title),
                    stringResource(if (partyActive) R.string.games_party_body else R.string.games_poll_unavailable),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(6.dp),
            ) {
                itemsIndexed(tiles) { index, tile ->
                    FocusSurface(
                        onClick = tile.onClick,
                        modifier = Modifier.height(118.dp).then(if (index == 0) Modifier.initialFocus() else Modifier),
                        enabled = partyActive,
                    ) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(tile.icon, contentDescription = null, tint = if (it) OnFocus else Accent, modifier = Modifier.size(30.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(tile.title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(tile.body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (it) OnFocus else TextSecondary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = Accent)
                Text(stringResource(R.string.play_title_lights), style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                listOf(
                    PartyPlay.Lights.RAINBOW to R.string.lights_rainbow,
                    PartyPlay.Lights.WAVE to R.string.lights_wave,
                    PartyPlay.Lights.PULSE to R.string.lights_pulse,
                    PartyPlay.Lights.CANDLE to R.string.lights_candle,
                ).forEach { (lights, label) ->
                    TvButton(stringResource(label), { onLights(lights) }, selected = play.lights == lights, enabled = partyActive)
                }
                TvButton(stringResource(R.string.lights_off), { onLights(null) }, ghost = true, enabled = partyActive && play.lights != null)
            }
        }
        play.treasureUrl?.let { TreasureOverlay(it, play.treasureFinders) }
    }
}

/** Gioco in corso: cosa succede sui telefoni, con il QR della festa e le monete a lato. */
@Composable
fun PlayScreen(
    play: PlayUiState,
    url: String?,
    photos: (String) -> List<PhotoItem>,
    original: PhotoItem?,
    onReveal: () -> Unit,
    onVote: () -> Unit,
    onAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val game = play.game
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(
                stringResource(R.string.party_games_kicker),
                game?.let { stringResource(kindTitle(it.kind)) } ?: stringResource(R.string.games_party_title),
                game?.prompt,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, modifier = if (game == null) Modifier.initialFocus() else Modifier, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        if (game == null) return@Column
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                PlayContent(game, photos, original)
            }
            PlaySidePanel(play, url, Modifier.width(320.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val endsWithReveal = game.kind !in setOf(PartyPlay.Kind.COMPLIMENTS, PartyPlay.Kind.DRAWING_WALL)
            if (game.kind == PartyPlay.Kind.MOLE && !game.voting && !game.revealed) {
                TvButton(stringResource(R.string.play_vote), onVote, modifier = Modifier.initialFocus(game.round), icon = Icons.Rounded.Check, selected = true)
            } else if (endsWithReveal && !game.revealed) {
                TvButton(stringResource(R.string.play_reveal), onReveal, modifier = Modifier.initialFocus(game.round), icon = Icons.Rounded.Check, selected = true)
            }
            TvButton(
                stringResource(R.string.play_new_round),
                onAgain,
                modifier = if (game.revealed || !endsWithReveal) Modifier.initialFocus(game.round to game.revealed) else Modifier,
                icon = Icons.Rounded.Refresh,
            )
        }
    }
}

@Composable
private fun PlayContent(game: PartyPlay.State, photos: (String) -> List<PhotoItem>, original: PhotoItem?) {
    when (game.kind) {
        PartyPlay.Kind.DRAW_GUESS -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val drawing = game.entries.lastOrNull { it.drawing != null }?.drawing
            DrawingCanvas(drawing, Modifier.fillMaxHeight().aspectRatio(1f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                game.special?.let { Text(stringResource(R.string.play_drawer, it.name), style = MaterialTheme.typography.headlineSmall, color = TextPrimary) }
                val winner = game.winner
                when {
                    winner != null -> BigText(stringResource(R.string.play_guessed, winner.name, game.secret.orEmpty()))
                    game.revealed -> BigText(stringResource(R.string.play_word, game.secret.orEmpty()))
                    else -> game.entries.filter { it.text != null }.takeLast(GUESSES_SHOWN).asReversed().forEach { guess ->
                        Text("${guess.name ?: "?"}: ${guess.text}", style = MaterialTheme.typography.titleMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        PartyPlay.Kind.DRAWING_WALL -> {
            val drawings = game.entries.filter { it.drawing != null }
            if (drawings.isEmpty()) Waiting(stringResource(R.string.play_waiting)) else LazyVerticalGrid(columns = GridCells.Fixed(5), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(drawings.takeLast(MAX_WALL), key = { _, entry -> entry.id }) { _, entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DrawingCanvas(entry.drawing, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(entry.name ?: "?", style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        PartyPlay.Kind.STORY, PartyPlay.Kind.CAPSULE -> {
            if (!game.revealed) {
                val plural = if (game.kind == PartyPlay.Kind.STORY) R.plurals.play_sentences else R.plurals.play_capsule_entries
                Waiting(quantityStringResource(plural, game.entries.size))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (game.kind == PartyPlay.Kind.STORY) game.secret?.let { item { StoryLine(it, null) } }
                    items(game.entries, key = { it.id }) { entry -> StoryLine(entry.text.orEmpty(), entry.name) }
                }
            }
        }
        PartyPlay.Kind.WHISPER -> {
            if (!game.revealed) {
                Waiting(stringResource(R.string.play_whisper_steps, game.entries.size, PartyPlay.MAX_WHISPER_STEPS))
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    game.secret?.let { item { WhisperCard(it, null, null) } }
                    items(game.entries, key = { it.id }) { entry -> WhisperCard(entry.text, entry.drawing, entry.name) }
                }
            }
        }
        PartyPlay.Kind.COMPLIMENTS -> Waiting(stringResource(R.string.play_compliments_hint))
        PartyPlay.Kind.MISSIONS, PartyPlay.Kind.PAPARAZZI -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (game.kind == PartyPlay.Kind.PAPARAZZI) game.special?.let { BigText(stringResource(R.string.play_paparazzi_star, it.name)) }
            PhotoGrid(photos(game.round), stringResource(R.string.play_waiting_photos))
        }
        PartyPlay.Kind.SAME_MOMENT -> {
            val now by produceState(System.currentTimeMillis(), game.round) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(250)
                }
            }
            val left = ((game.momentAtMillis - now + 999) / 1000).toInt()
            when {
                left > 0 -> Waiting(stringResource(R.string.play_moment_in, left), large = true)
                now < game.momentAtMillis + PartyPlay.MOMENT_WINDOW_MS -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigText(stringResource(R.string.play_moment_now))
                    PhotoGrid(photos(game.round), null)
                }
                else -> PhotoGrid(photos(game.round), stringResource(R.string.play_waiting_photos))
            }
        }
        PartyPlay.Kind.RECREATE -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (original != null) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kicker(stringResource(R.string.play_original))
                    PhotoContent(original, FitMode.CONTAIN, Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(16.dp)))
                }
            }
            Box(Modifier.weight(1f)) { PhotoGrid(photos(game.round), stringResource(R.string.play_waiting_photos), columns = 2) }
        }
        PartyPlay.Kind.MOLE -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val mole = game.special
            when {
                game.revealed && mole != null -> {
                    val caught = game.votes.maxByOrNull { it.value }?.key == mole.name && game.votes.isNotEmpty()
                    BigText(stringResource(if (caught) R.string.play_mole_caught else R.string.play_mole_free, mole.name))
                    game.secret?.let { Text(stringResource(R.string.play_mole_mission, it), style = MaterialTheme.typography.titleMedium, color = TextSecondary) }
                }
                game.voting -> {
                    Text(stringResource(R.string.play_mole_voting), style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    val total = game.votes.values.sum().coerceAtLeast(1)
                    game.votes.entries.sortedByDescending { it.value }.forEach { (name, votes) ->
                        VoteBar(name, votes, votes.toFloat() / total)
                    }
                }
                else -> Waiting(stringResource(R.string.play_mole_playing))
            }
        }
    }
}

@Composable
private fun BigText(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = Accent, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Waiting(text: String, large: Boolean = false) {
    Box(Modifier.fillMaxSize().background(SurfaceDark, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun StoryLine(text: String, name: String?) {
    Column(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        if (name != null) Text(name, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

@Composable
private fun WhisperCard(text: String?, drawing: String?, name: String?) {
    Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (drawing != null) {
            DrawingCanvas(drawing, Modifier.fillMaxWidth().aspectRatio(1f))
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(SurfaceRaised, RoundedCornerShape(16.dp)).padding(20.dp), contentAlignment = Alignment.Center) {
                Text(text.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = TextPrimary, textAlign = TextAlign.Center)
            }
        }
        Text(name ?: stringResource(R.string.play_whisper_start), style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PhotoGrid(photos: List<PhotoItem>, empty: String?, columns: Int = 4) {
    if (photos.isEmpty()) {
        if (empty != null) Waiting(empty)
        return
    }
    LazyVerticalGrid(columns = GridCells.Fixed(columns), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(photos.takeLast(MAX_GRID_PHOTOS), key = { _, photo -> photo.id }) { _, photo ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PhotoContent(photo, FitMode.CROP, Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(SurfaceDark, RoundedCornerShape(12.dp)), placeholder = false)
                photo.sender?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun VoteBar(label: String, votes: Int, fraction: Float) {
    Box(Modifier.fillMaxWidth().height(54.dp).background(SurfaceDark, RoundedCornerShape(14.dp))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(SurfaceRaised, RoundedCornerShape(14.dp)))
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(quantityStringResource(R.plurals.poll_votes, votes), style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        }
    }
}

/** QR della festa e le monete dei primi tre, a lato del gioco. */
@Composable
private fun PlaySidePanel(play: PlayUiState, url: String?, modifier: Modifier) {
    Column(modifier.background(SurfaceDark, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (url != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                QrCode(url, stringResource(R.string.phone_scan), size = 84.dp, padding = 6.dp)
                Text(stringResource(R.string.phone_scan), style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
        val count = play.game?.entries?.size ?: 0
        if (count > 0) Text(quantityStringResource(R.plurals.play_contributions, count), style = MaterialTheme.typography.titleSmall, color = TextPrimary)
        if (play.coins.isNotEmpty()) {
            Kicker(stringResource(R.string.play_title_coins))
            play.coins.take(3).forEachIndexed { index, coins ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${index + 1}. ${coins.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(quantityStringResource(R.plurals.coins_count, coins.coins), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }
        }
    }
}

/** Disegno di un telefono: tratti colorati su fondo bianco, nelle coordinate 0–1 della tela. */
@Composable
fun DrawingCanvas(drawing: String?, modifier: Modifier = Modifier) {
    val strokes = remember(drawing) { drawing?.let(PartyPlay::strokes).orEmpty() }
    Canvas(modifier.background(Color.White, RoundedCornerShape(16.dp))) {
        val side = size.minDimension
        val width = side * .012f
        strokes.forEach { (color, points) ->
            val paint = DrawingColors.getOrElse(color) { DrawingColors[0] }
            if (points.size == 2) {
                drawCircle(paint, radius = width, center = Offset(points[0] * size.width, points[1] * size.height))
                return@forEach
            }
            val path = Path()
            path.moveTo(points[0] * size.width, points[1] * size.height)
            var index = 2
            while (index + 1 < points.size) {
                path.lineTo(points[index] * size.width, points[index + 1] * size.height)
                index += 2
            }
            drawPath(path, paint, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Foto nata in un gioco: la sua etichetta in alto al centro dello slideshow. */
@Composable
fun BoxScope.GameTagBadge(text: String) {
    Text(
        text,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
            .background(Accent, RoundedCornerShape(50))
            .padding(horizontal = 24.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        color = OnAccent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Caccia al QR: il codice grande in basso a destra finché qualcuno non lo trova. */
@Composable
fun BoxScope.TreasureOverlay(url: String, finders: List<String> = emptyList()) {
    Column(
        Modifier.align(Alignment.BottomEnd).padding(36.dp)
            .background(SurfaceRaised.copy(alpha = .96f), RoundedCornerShape(24.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.treasure_title), style = MaterialTheme.typography.titleMedium, color = Accent, textAlign = TextAlign.Center)
        QrCode(url, stringResource(R.string.treasure_title), size = 220.dp)
        if (finders.isNotEmpty()) Text(stringResource(R.string.treasure_found, finders.joinToString(" · ")), style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

// ---------------------------------------------------------------------------------------------
// Torneo delle foto
// ---------------------------------------------------------------------------------------------

/** Torneo a eliminazione: due foto alla volta, i telefoni votano, la più votata passa il turno. */
@Composable
fun TournamentScreen(photos: List<PhotoItem>, play: PhonePlay?, onBack: () -> Unit) {
    val entrants = remember { photos.shuffled().take(if (photos.size >= 8) 8 else 4) }
    var round by remember { mutableStateOf(entrants) }
    var winners by remember { mutableStateOf(emptyList<PhotoItem>()) }
    var match by remember { mutableIntStateOf(0) }
    StopPhoneGameOnExit(play, active = true)
    val question = stringResource(R.string.tournament_question)
    val choices = listOf(stringResource(R.string.tournament_a), stringResource(R.string.tournament_b))
    val champion = round.singleOrNull()
    val pair = if (champion == null && round.size >= 2) round[match * 2] to round[match * 2 + 1] else null
    LaunchedEffect(pair?.first?.id, pair?.second?.id) {
        if (pair != null) play?.game?.askPoll(question, choices)
    }
    val tally by produceState(listOf(0, 0), play, pair) {
        while (true) {
            value = play?.game?.tally()?.takeIf { it.size == 2 } ?: listOf(0, 0)
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(
                stringResource(R.string.party_games_kicker),
                stringResource(R.string.play_title_tournament),
                when {
                    entrants.size < 4 || play == null -> stringResource(if (play == null) R.string.games_poll_unavailable else R.string.tournament_need)
                    champion != null -> stringResource(R.string.tournament_champion)
                    round.size == 2 -> stringResource(R.string.tournament_final)
                    round.size == 4 -> stringResource(R.string.tournament_semi)
                    else -> stringResource(R.string.tournament_quarter)
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, modifier = if (pair == null) Modifier.initialFocus() else Modifier, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        if (entrants.size < 4 || play == null) return@Column
        if (champion != null) {
            PhotoContent(champion, FitMode.CONTAIN, Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(20.dp)))
            return@Column
        }
        if (pair == null) return@Column
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(pair.first, pair.second).forEachIndexed { index, photo ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhotoContent(photo, FitMode.CONTAIN, Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(16.dp)))
                    ChoiceLabel("${choices[index]} · ${quantityStringResource(R.plurals.poll_votes, tally.getOrElse(index) { 0 })}", highlighted = tally.getOrElse(index) { 0 } > tally.getOrElse(1 - index) { 0 })
                }
            }
        }
        TvButton(
            stringResource(R.string.tournament_next),
            onClick = {
                val votes = play.game.tally()
                val first = votes.getOrElse(0) { 0 }
                val second = votes.getOrElse(1) { 0 }
                val winner = when {
                    first > second -> pair.first
                    second > first -> pair.second
                    else -> listOf(pair.first, pair.second).random()
                }
                val next = winners + winner
                if (match * 2 + 2 >= round.size) {
                    round = next
                    winners = emptyList()
                    match = 0
                    if (next.size == 1) play.game.stop()
                } else {
                    winners = next
                    match++
                }
            },
            modifier = Modifier.initialFocus(pair.first.id),
            icon = Icons.Rounded.Check,
            selected = true,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Scommesse
// ---------------------------------------------------------------------------------------------

private class BetPreset(val question: String, val choices: List<String>?)

/** Scommesse con i telefoni: si scommette, si chiude, e chi aveva ragione vince monete. */
@Composable
fun BetsScreen(play: PhonePlay?, candidates: () -> List<String>, onSettle: (Int) -> Int, onBack: () -> Unit) {
    val presets = listOf(
        BetPreset(stringResource(R.string.bets_q_cake), null),
        BetPreset(stringResource(R.string.bets_q_last), null),
        BetPreset(stringResource(R.string.bets_q_song), stringResource(R.string.bets_c_song).split('|')),
        BetPreset(stringResource(R.string.bets_q_photos), stringResource(R.string.bets_c_photos).split('|')),
        BetPreset(stringResource(R.string.bets_q_speech), stringResource(R.string.bets_c_speech).split('|')),
    )
    var question by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf(emptyList<String>()) }
    var closed by remember { mutableStateOf(false) }
    var winners by remember { mutableStateOf<Int?>(null) }
    StopPhoneGameOnExit(play, active = question != null)
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(
                stringResource(R.string.party_games_kicker),
                question ?: stringResource(R.string.play_title_bets),
                when {
                    play == null -> stringResource(R.string.games_poll_unavailable)
                    question == null -> stringResource(R.string.bets_choose)
                    winners != null -> quantityStringResource(R.plurals.bets_winners, winners!!)
                    closed -> stringResource(R.string.bets_outcome)
                    else -> stringResource(R.string.play_body_bets)
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, modifier = if (play == null) Modifier.initialFocus() else Modifier, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        if (play == null) return@Column
        val current = question
        if (current == null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(presets) { index, preset ->
                    TvButton(
                        preset.question,
                        onClick = {
                            val options = preset.choices ?: candidates().shuffled().take(4)
                            if (options.size >= 2) {
                                question = preset.question
                                choices = options
                                closed = false
                                winners = null
                                play.game.askPoll(preset.question, options)
                            }
                        },
                        modifier = if (index == 0) Modifier.initialFocus() else Modifier,
                        icon = Icons.Rounded.Payments,
                        enabled = preset.choices != null || candidates().size >= 2,
                    )
                }
            }
            return@Column
        }
        val tally by produceState(emptyList<Int>(), play, current) {
            while (true) {
                value = play.game.tally()
                delay(500)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                choices.forEachIndexed { index, choice ->
                    val votes = tally.getOrElse(index) { 0 }
                    if (closed && winners == null) {
                        // Si sceglie com'è andata: chi l'aveva detto vince le monete.
                        TvButton(
                            "$choice · ${quantityStringResource(R.plurals.poll_votes, votes)}",
                            { winners = onSettle(index) },
                            modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.initialFocus(current to closed) else Modifier),
                        )
                    } else {
                        VoteBar(choice, votes, if (closed) votes.toFloat() / tally.sum().coerceAtLeast(1) else 0f)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!closed) {
                        TvButton(stringResource(R.string.bets_close), { closed = true; play.game.reveal() }, modifier = Modifier.initialFocus(current), icon = Icons.Rounded.Check, selected = true)
                    }
                    if (winners != null || !closed) {
                        TvButton(stringResource(R.string.bets_new), { play.game.stop(); question = null }, modifier = if (winners != null) Modifier.initialFocus(winners) else Modifier, icon = Icons.Rounded.Refresh)
                    }
                }
            }
            PhonePlayPanel(play, revealed = false, modifier = Modifier.width(340.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Roulette dei brindisi
// ---------------------------------------------------------------------------------------------

/** La roulette sceglie chi fa il prossimo brindisi; il nome arriva anche sui telefoni. */
@Composable
fun ToastScreen(candidates: () -> List<String>, onAnnounce: (String) -> Unit, onBack: () -> Unit) {
    var shown by remember { mutableStateOf<String?>(null) }
    var spinning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val names = remember { candidates() }
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(
                stringResource(R.string.party_games_kicker),
                stringResource(R.string.play_title_toast),
                if (names.isEmpty()) stringResource(R.string.lottery_none) else stringResource(R.string.play_body_toast),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.WineBar, contentDescription = null, tint = Accent, modifier = Modifier.size(64.dp))
                Text(
                    shown?.let { if (spinning) it else stringResource(R.string.toast_winner, it) } ?: stringResource(R.string.toast_ready),
                    style = MaterialTheme.typography.displayMedium,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TvButton(
            stringResource(R.string.toast_spin),
            onClick = {
                if (spinning || names.isEmpty()) return@TvButton
                val winner = names.random()
                spinning = true
                scope.launch {
                    if (!reduced) {
                        val start = System.nanoTime()
                        while (System.nanoTime() - start < TOAST_SPIN_NANOS) {
                            shown = names.random()
                            delay(85)
                        }
                    }
                    shown = winner
                    spinning = false
                    onAnnounce(winner)
                }
            },
            modifier = Modifier.initialFocus(),
            icon = Icons.Rounded.WineBar,
            selected = true,
            enabled = names.isNotEmpty(),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Monete della festa
// ---------------------------------------------------------------------------------------------

@Composable
fun CoinsScreen(coins: List<PartyPlay.Coins>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.party_games_kicker), stringResource(R.string.play_title_coins), stringResource(R.string.coins_how), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.initialFocus(), icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        if (coins.isEmpty()) {
            Waiting(stringResource(R.string.coins_empty))
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(coins) { index, entry ->
                Row(
                    Modifier.fillMaxWidth().background(if (index == 0) Accent else SurfaceDark, RoundedCornerShape(14.dp)).padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val color = if (index == 0) OnAccent else TextPrimary
                    Text("${index + 1}.", style = MaterialTheme.typography.headlineSmall, color = color, modifier = Modifier.width(56.dp))
                    Text(entry.name, style = MaterialTheme.typography.headlineSmall, color = color, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(quantityStringResource(R.plurals.coins_count, entry.coins), style = MaterialTheme.typography.titleLarge, color = color)
                }
            }
        }
    }
}

private const val GUESSES_SHOWN = 6
private const val MAX_WALL = 20
private const val MAX_GRID_PHOTOS = 24
private const val TOAST_SPIN_NANOS = 3_000_000_000L
