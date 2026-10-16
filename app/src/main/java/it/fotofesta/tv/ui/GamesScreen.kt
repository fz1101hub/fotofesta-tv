package it.fotofesta.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R

/** Numero minimo di foto perché i giochi abbiano senso (almeno due coppie / due risposte). */
const val MIN_GAME_PHOTOS = 2

/** Larghezza delle schede dei giochi: nella fila, che scorre di lato, se ne vedono tre. */
private val GAME_TILE_WIDTH = 290.dp

@Composable
fun GamesScreen(
    photoCount: Int,
    whoTookItPlayable: Boolean,
    onBack: () -> Unit,
    onMemory: () -> Unit,
    onMystery: () -> Unit,
    onPuzzle: () -> Unit,
    onWhoTookIt: () -> Unit,
) {
    val playable = photoCount >= MIN_GAME_PHOTOS
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(
                stringResource(R.string.games_kicker),
                stringResource(R.string.games_title),
                stringResource(R.string.games_subtitle),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(
                stringResource(R.string.action_back),
                onBack,
                modifier = if (playable) Modifier else Modifier.initialFocus(),
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
            )
        }
        // Fila che scorre di lato, come le file della TV: a sinistra si allarga oltre il margine quanto
        // basta perché l'ingrandimento delle schede con il fuoco non venga tagliato, restando allineata
        // al titolo; a destra arriva al bordo dello schermo, così la scheda successiva si intravede
        // invece di finire tagliata di netto sul margine.
        LazyRow(
            modifier = Modifier.fillMaxWidth().bleedHorizontally(start = TILE_BLEED, end = SCREEN_MARGIN),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(start = TILE_BLEED, end = SCREEN_MARGIN, top = 10.dp, bottom = 10.dp),
        ) {
            item {
                GameTile(
                    stringResource(R.string.games_memory_title),
                    stringResource(R.string.games_memory_body),
                    Icons.Rounded.GridView,
                    onMemory,
                    Modifier.width(GAME_TILE_WIDTH).then(if (playable) Modifier.initialFocus() else Modifier),
                    enabled = playable,
                )
            }
            item {
                GameTile(
                    stringResource(R.string.games_mystery_title),
                    stringResource(R.string.games_mystery_body),
                    Icons.Rounded.Visibility,
                    onMystery,
                    Modifier.width(GAME_TILE_WIDTH),
                    enabled = playable,
                )
            }
            item {
                GameTile(
                    stringResource(R.string.games_puzzle_title),
                    stringResource(R.string.games_puzzle_body),
                    Icons.Rounded.Extension,
                    onPuzzle,
                    Modifier.width(GAME_TILE_WIDTH),
                    enabled = playable,
                )
            }
            item {
                // Si gioca solo con le foto della festa: senza, la scheda spiega cosa serve.
                GameTile(
                    stringResource(R.string.games_who_title),
                    stringResource(if (whoTookItPlayable) R.string.games_who_body else R.string.games_who_unavailable),
                    Icons.Rounded.PhotoCamera,
                    onWhoTookIt,
                    Modifier.width(GAME_TILE_WIDTH),
                    enabled = whoTookItPlayable,
                )
            }
        }
        Text(
            if (playable) quantityStringResource(R.plurals.games_active_collection, photoCount)
            else stringResource(R.string.games_need_photos, MIN_GAME_PHOTOS),
            color = TextSecondary,
        )
    }
}

/** Margine in più a sinistra della fila, per l'ingrandimento delle schede con il fuoco. */
private val TILE_BLEED = 10.dp

/** Margine laterale della schermata: a destra la fila lo attraversa fino al bordo dello schermo. */
private val SCREEN_MARGIN = 48.dp

/** Allarga l'elemento di [start] a sinistra e di [end] a destra oltre lo spazio che gli spetta. */
private fun Modifier.bleedHorizontally(start: Dp, end: Dp): Modifier = layout { measurable, constraints ->
    val left = start.roundToPx()
    val extra = left + end.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
        ),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-left, 0) }
}

/** Scheda grande di un gioco: icona, nome e descrizione. */
@Composable
private fun GameTile(
    title: String,
    body: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FocusSurface(
        onClick = onClick,
        // Icona, nome e tre righe di descrizione (in francese, spagnolo e tedesco ne servono tre).
        modifier = modifier.height(208.dp),
        shape = RoundedCornerShape(16.dp),
        color = SurfaceDark,
        border = BorderStroke(1.dp, TextPrimary.copy(alpha = .08f)),
        focusScale = 1.04f,
        enabled = enabled,
    ) { focused ->
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(48.dp).background(if (focused) OnFocus else Accent, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (focused) FocusWhite else OnAccent, modifier = Modifier.size(28.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) OnFocusSecondary else TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun NotEnoughPhotos(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SectionTitle(
            title,
            stringResource(R.string.games_more_photos_title),
            stringResource(R.string.games_more_photos_body, MIN_GAME_PHOTOS),
        )
        TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.initialFocus(), icon = Icons.AutoMirrored.Rounded.ArrowBack)
    }
}
