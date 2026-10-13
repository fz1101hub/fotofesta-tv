package it.fotofesta.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R

/** Numero minimo di foto perché i giochi abbiano senso (almeno due coppie / due risposte). */
const val MIN_GAME_PHOTOS = 2

@Composable
fun GamesScreen(photoCount: Int, onBack: () -> Unit, onMemory: () -> Unit, onMystery: () -> Unit) {
    val playable = photoCount >= MIN_GAME_PHOTOS
    Column(Modifier.fillMaxSize().padding(46.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            SourceCard(
                stringResource(R.string.games_memory_title),
                stringResource(R.string.games_memory_body),
                Icons.Rounded.GridView,
                Coral,
                onMemory,
                Modifier.weight(1f).then(if (playable) Modifier.initialFocus() else Modifier),
                enabled = playable,
            )
            SourceCard(
                stringResource(R.string.games_mystery_title),
                stringResource(R.string.games_mystery_body),
                Icons.Rounded.Visibility,
                Cyan,
                onMystery,
                Modifier.weight(1f),
                enabled = playable,
            )
        }
        Text(
            if (playable) quantityStringResource(R.plurals.games_active_collection, photoCount)
            else stringResource(R.string.games_need_photos, MIN_GAME_PHOTOS),
            color = TextSecondary,
        )
    }
}

@Composable
fun NotEnoughPhotos(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(46.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SectionTitle(
            title,
            stringResource(R.string.games_more_photos_title),
            stringResource(R.string.games_more_photos_body, MIN_GAME_PHOTOS),
        )
        TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.initialFocus(), icon = Icons.AutoMirrored.Rounded.ArrowBack)
    }
}
