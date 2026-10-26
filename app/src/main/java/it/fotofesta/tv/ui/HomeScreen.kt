package it.fotofesta.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.FitMode

@Composable
fun HomeScreen(
    state: AppState,
    onGoogle: () -> Unit,
    onUsb: () -> Unit,
    onDemo: () -> Unit,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onGames: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(GraphiteBackground)) {
        AppHeader(state.sourceLabel, state.castSenderConnected)

        Box(Modifier.fillMaxWidth().weight(1f)) {
            state.photos.getOrNull(state.currentIndex)?.let { photo ->
                PhotoContent(photo, FitMode.CROP, Modifier.fillMaxSize())
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0.0f to GraphiteBackground,
                        0.38f to GraphiteBackground.copy(alpha = .98f),
                        0.75f to GraphiteBackground.copy(alpha = .67f),
                        1f to GraphiteBackground.copy(alpha = .28f),
                    )
                )
            )
            Column(
                Modifier.fillMaxSize().padding(horizontal = 46.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("LA TUA GALLERIA PERSONALE", color = GraphiteSecondary, style = MaterialTheme.typography.labelLarge)
                Text(
                    "I tuoi ricordi.\nAl centro di tutto.",
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                )
                Text(
                    "${state.photos.size} fotografie pronte · ${state.sourceLabel}",
                    color = GraphiteSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(
                    Modifier.padding(top = 26.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TvButton(
                        "Avvia slideshow",
                        onPlay,
                        icon = Icons.Rounded.PlayArrow,
                        enabled = state.photos.isNotEmpty(),
                        primary = true,
                        reducedMotion = state.settings.reducedMotion,
                    )
                    TvButton("Gestisci foto", onSources, reducedMotion = state.settings.reducedMotion)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 46.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Text("SCEGLI UNA SORGENTE", color = GraphiteSecondary, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SourceCard(
                    "Google Foto", "Scegli le immagini dal telefono.",
                    Icons.Rounded.Cloud, GraphiteWhite, onGoogle,
                    Modifier.weight(1f), state.settings.reducedMotion,
                )
                SourceCard(
                    "Unità USB", "Fotografie da una cartella locale.",
                    Icons.Rounded.Usb, GraphiteWhite, onUsb,
                    Modifier.weight(1f), state.settings.reducedMotion,
                )
                SourceCard(
                    "Foto demo", "Esplora subito l'app.",
                    Icons.Rounded.PlayArrow, GraphiteWhite, onDemo,
                    Modifier.weight(1f), state.settings.reducedMotion,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Giochi con le foto", onGames, icon = Icons.Rounded.SportsEsports, reducedMotion = state.settings.reducedMotion)
                TvButton("Impostazioni", onSettings, icon = Icons.Rounded.Settings, reducedMotion = state.settings.reducedMotion)
            }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = GraphiteWhite)
            }
        }
    }
}
