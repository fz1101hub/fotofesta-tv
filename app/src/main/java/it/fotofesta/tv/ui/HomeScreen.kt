package it.fotofesta.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.AppState

@Composable
fun HomeScreen(
    state: AppState,
    showGoogle: Boolean,
    onGoogle: () -> Unit,
    onUsb: () -> Unit,
    onDemo: () -> Unit,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onGames: () -> Unit,
) {
    // Su TV serve un elemento a fuoco all'apertura, altrimenti il primo tasto del telecomando va perso.
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF1A2948), Navy), radius = 1_250f)
        )
    ) {
        Column(Modifier.fillMaxSize()) {
            AppHeader(state.sourceLabel, state.castSenderConnected)
            // Scorre solo se il testo della TV è impostato su "grande": il fuoco porta in vista l'ultima riga.
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    SectionTitle(
                        stringResource(R.string.home_kicker),
                        stringResource(R.string.home_title),
                        stringResource(R.string.home_subtitle),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    TvButton(
                        stringResource(R.string.action_start_slideshow),
                        onPlay,
                        modifier = Modifier.focusRequester(primaryFocus),
                        icon = Icons.Rounded.PlayArrow,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    // Una build di release senza client OAuth non mostra Google Foto.
                    if (showGoogle) {
                        SourceCard(
                            stringResource(R.string.home_google_title),
                            stringResource(R.string.home_google_body),
                            Icons.Rounded.Cloud,
                            Cyan,
                            onGoogle,
                            Modifier.weight(1f),
                        )
                    }
                    SourceCard(
                        stringResource(R.string.home_usb_title),
                        stringResource(R.string.home_usb_body),
                        Icons.Rounded.Usb,
                        Amber,
                        onUsb,
                        Modifier.weight(1f),
                    )
                    SourceCard(
                        stringResource(R.string.home_demo_title),
                        stringResource(R.string.home_demo_body),
                        Icons.Rounded.PlayArrow,
                        Coral,
                        onDemo,
                        Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton(stringResource(R.string.action_settings), onSettings, icon = Icons.Rounded.Settings)
                    TvButton(stringResource(R.string.home_games), onGames, icon = Icons.Rounded.SportsEsports)
                }
            }
        }
    }
}
