package it.fotofesta.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.SourceType
import kotlinx.coroutines.delay

private const val BACKDROP_INTERVAL_MS = 9_000L
private const val BACKDROP_PHOTOS = 12

/**
 * Home: la raccolta in uso occupa lo schermo, con "Avvia slideshow" già a fuoco e "Avvia la festa"
 * accanto; sotto, la riga delle sorgenti (quella in uso segnata "In uso"), Giochi e Impostazioni.
 */
@Composable
fun HomeScreen(
    state: AppState,
    showGoogle: Boolean,
    onGoogle: () -> Unit,
    onGuests: () -> Unit,
    onUsb: () -> Unit,
    onDemo: () -> Unit,
    onPlay: () -> Unit,
    onStartParty: () -> Unit,
    onSettings: () -> Unit,
    onGames: () -> Unit,
) {
    val collection = state.collection
    val inUse = stringResource(R.string.home_in_use)
    Box(Modifier.fillMaxSize().background(Background)) {
        HomeBackdrop(state)
        // Scuro a sinistra, dove ci sono i testi, e in basso, sotto le schede; la foto resta visibile a destra.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Background.copy(alpha = .97f),
                    .34f to Background.copy(alpha = .86f),
                    .66f to Background.copy(alpha = .25f),
                    1f to Background.copy(alpha = .1f),
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    .45f to Background.copy(alpha = 0f),
                    .7f to Background.copy(alpha = .6f),
                    1f to Background.copy(alpha = .98f),
                )
            )
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 27.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark()
            Spacer(Modifier.weight(1f))
            if (state.guests.active) {
                PartyChip(quantityStringResource(R.plurals.home_party_chip, state.guests.shown))
                Spacer(Modifier.width(14.dp))
            }
            ClockText()
        }

        Column(
            Modifier.padding(start = 48.dp, top = 104.dp).width(500.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Kicker(stringResource(R.string.home_collection_kicker))
            Text(
                collection.title,
                style = MaterialTheme.typography.displayLarge,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                collectionDetail(state),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton(
                    stringResource(R.string.action_start_slideshow),
                    onPlay,
                    modifier = Modifier.initialFocus(),
                    icon = Icons.Rounded.PlayArrow,
                )
                TvButton(
                    stringResource(if (state.guests.active) R.string.home_party_running else R.string.home_start_party),
                    if (state.guests.active) onGuests else onStartParty,
                    icon = Icons.Rounded.Groups,
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.home_choose_photos), style = MaterialTheme.typography.titleSmall, color = TextSecondary)
            Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SourceTile(
                    stringResource(R.string.home_tile_guests_title),
                    stringResource(R.string.home_tile_guests_body),
                    Icons.Rounded.Groups,
                    onGuests,
                    Modifier.weight(1f),
                    badge = inUse.takeIf { collection.source == SourceType.GUESTS },
                )
                // Una build di release senza client OAuth non mostra Google Foto.
                if (showGoogle) {
                    SourceTile(
                        stringResource(R.string.home_google_title),
                        stringResource(R.string.home_tile_google_body),
                        Icons.Rounded.Cloud,
                        onGoogle,
                        Modifier.weight(1f),
                        badge = inUse.takeIf { collection.source == SourceType.GOOGLE_PHOTOS },
                    )
                }
                // Cartelle di un disco USB o di un server della rete di casa, dal browser di cartelle.
                val folders = collection.source == SourceType.USB || collection.source == SourceType.NETWORK || collection.source == SourceType.ICLOUD
                SourceTile(
                    stringResource(R.string.home_folders_title),
                    if (folders) {
                        quantityStringResource(R.plurals.home_collection_count, collection.count)
                    } else {
                        stringResource(R.string.home_tile_usb_body)
                    },
                    Icons.Rounded.FolderOpen,
                    onUsb,
                    Modifier.weight(1f),
                    badge = inUse.takeIf { folders },
                )
                SourceTile(
                    stringResource(R.string.collection_demo),
                    stringResource(R.string.home_tile_demo_body),
                    Icons.Rounded.Image,
                    onDemo,
                    Modifier.weight(1f),
                    badge = inUse.takeIf { collection.source == SourceType.DEMO },
                )
                SmallTile(stringResource(R.string.home_tile_games), Icons.Rounded.SportsEsports, onGames, Modifier.width(108.dp))
                SmallTile(stringResource(R.string.action_settings), Icons.Rounded.Settings, onSettings, Modifier.width(108.dp))
            }
        }
    }
}

/** "248 foto · Hard disk USB · 12 dagli invitati": quante foto, da dove, e quelle degli invitati in più. */
@Composable
private fun collectionDetail(state: AppState): String {
    val collection = state.collection
    val parts = mutableListOf(quantityStringResource(R.plurals.home_collection_count, collection.count))
    parts += when (collection.source) {
        SourceType.USB -> stringResource(R.string.home_usb_title)
        SourceType.NETWORK -> stringResource(R.string.home_network_title)
        SourceType.ICLOUD -> stringResource(R.string.home_icloud_title)
        SourceType.GOOGLE_PHOTOS -> stringResource(R.string.home_google_title)
        SourceType.DEMO -> stringResource(R.string.home_tile_demo_body)
        SourceType.GUESTS -> stringResource(R.string.home_tile_guests_body)
    }
    if (collection.source != SourceType.GUESTS && collection.source != SourceType.DEMO && state.guests.shown > 0) {
        parts += quantityStringResource(R.plurals.home_collection_guests, state.guests.shown)
    }
    return parts.joinToString(" · ")
}

/** Sfondo: le foto della raccolta in uso, una alla volta, con una dissolvenza lenta. */
@Composable
private fun HomeBackdrop(state: AppState) {
    val photos = remember(state.photos) { state.photos.asSequence().filterNot { it.video }.take(BACKDROP_PHOTOS).toList() }
    if (photos.isEmpty()) return
    var index by remember(photos) { mutableIntStateOf(0) }
    LaunchedEffect(photos) {
        while (photos.size > 1) {
            delay(BACKDROP_INTERVAL_MS)
            index = (index + 1) % photos.size
        }
    }
    Crossfade(
        targetState = photos[index.coerceIn(0, photos.lastIndex)],
        animationSpec = if (LocalReducedMotion.current) snap() else tween(1_500),
        label = "homeBackdrop",
        modifier = Modifier.fillMaxSize(),
    ) { photo ->
        PhotoContent(photo, FitMode.CROP, Modifier.fillMaxSize(), contentDescription = null, placeholder = false)
    }
}
