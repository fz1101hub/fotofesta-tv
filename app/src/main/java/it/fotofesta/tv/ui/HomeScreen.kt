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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.model.AppState

@Composable
fun HomeScreen(
    state: AppState,
    onGoogle: () -> Unit,
    onUsb: () -> Unit,
    onDemo: () -> Unit,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onGames: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF1A2948), Navy), radius = 1_250f)
        )
    ) {
        Column(Modifier.fillMaxSize()) {
            AppHeader(state.sourceLabel, state.castSenderConnected)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 46.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    SectionTitle(
                        "I tuoi ricordi sul grande schermo",
                        "Scegli da dove iniziare",
                        "Google Foto resta sul tuo account. USB funziona anche senza internet.",
                    )
                    Spacer(Modifier.weight(1f))
                    TvButton("Avvia slideshow", onPlay, icon = Icons.Rounded.PlayArrow)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SourceCard(
                        "Google Foto",
                        "Seleziona dal telefono con QR. Nessun cast e nessun import manuale.",
                        Icons.Rounded.Cloud,
                        Cyan,
                        onGoogle,
                        Modifier.weight(1f),
                    )
                    SourceCard(
                        "Hard disk USB",
                        "Scegli una cartella. L’accesso resta solo su questa TV.",
                        Icons.Rounded.Usb,
                        Amber,
                        onUsb,
                        Modifier.weight(1f),
                    )
                    SourceCard(
                        "Prova subito",
                        "Quattro foto demo per esplorare transizioni, musica e giochi.",
                        Icons.Rounded.PlayArrow,
                        Coral,
                        onDemo,
                        Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton("Impostazioni", onSettings, icon = Icons.Rounded.Settings)
                    TvButton("Giochi con le foto", onGames, icon = Icons.Rounded.SportsEsports)
                }
                if (state.message != null) {
                    Text(state.message, style = MaterialTheme.typography.bodyLarge, color = Amber)
                }
            }
        }
    }
}
