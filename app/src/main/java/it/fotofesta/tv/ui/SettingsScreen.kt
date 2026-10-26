package it.fotofesta.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.TransitionType

private enum class SettingsCategory(val label: String) {
    PHOTOS("Foto e transizioni"),
    MUSIC("Musica"),
    ACCESSIBILITY("Accessibilità"),
    PRIVACY("Account e privacy"),
}

@Composable
fun SettingsScreen(
    state: AppState,
    onBack: () -> Unit,
    onChange: (SlideshowSettings) -> Unit,
    onPickMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onDisconnectGoogle: () -> Unit,
    onPlay: () -> Unit,
) {
    val settings = state.settings
    var category by remember { mutableStateOf(SettingsCategory.PHOTOS) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnettere Google Foto?") },
            text = { Text("I token e le fotografie memorizzate nella cache privata saranno cancellati da questa TV.") },
            confirmButton = {
                TvButton("Disconnetti e cancella", {
                    confirmDisconnect = false
                    onDisconnectGoogle()
                }, primary = true, reducedMotion = settings.reducedMotion)
            },
            dismissButton = {
                TvButton("Annulla", { confirmDisconnect = false }, reducedMotion = settings.reducedMotion)
            },
            containerColor = GraphiteSurface,
            titleContentColor = GraphiteWhite,
            textContentColor = GraphiteSecondary,
        )
    }

    Column(
        Modifier.fillMaxSize().background(GraphiteBackground).padding(horizontal = 46.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Personalizza l'esperienza", "Impostazioni", "Le modifiche vengono salvate solo su questa TV.")
            }
            TvButton("Indietro", onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack, reducedMotion = settings.reducedMotion)
            Spacer(Modifier.padding(horizontal = 6.dp))
            TvButton("Avvia", onPlay, icon = Icons.Rounded.PlayArrow, primary = true, reducedMotion = settings.reducedMotion)
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(
                Modifier.weight(.32f).background(GraphiteSurface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsCategory.entries.forEach { option ->
                    TvButton(
                        option.label, { category = option },
                        modifier = Modifier.fillMaxWidth(),
                        selected = category == option,
                        reducedMotion = settings.reducedMotion,
                    )
                }
            }
            Column(
                Modifier.weight(.68f).fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (category) {
                    SettingsCategory.PHOTOS -> {
                        Text("Foto e transizioni", style = MaterialTheme.typography.headlineMedium)
                        SettingRow("Durata", "Tempo di visualizzazione di ogni fotografia.") {
                            listOf(3, 5, 8, 12, 20).forEach { seconds ->
                                TvButton("${seconds}s", { onChange(settings.copy(intervalSeconds = seconds)) },
                                    selected = settings.intervalSeconds == seconds, reducedMotion = settings.reducedMotion)
                            }
                        }
                        SettingRow("Transizione", "Animazione tra due fotografie.") {
                            TransitionType.entries.forEach { transition ->
                                TvButton(transition.label, { onChange(settings.copy(transition = transition)) },
                                    selected = settings.transition == transition, reducedMotion = settings.reducedMotion)
                            }
                        }
                        SettingRow("Adattamento", "Foto intera o riempimento dello schermo.") {
                            FitMode.entries.forEach { fit ->
                                TvButton(fit.label, { onChange(settings.copy(fitMode = fit)) },
                                    selected = settings.fitMode == fit, reducedMotion = settings.reducedMotion)
                            }
                        }
                        SettingRow("Ordine casuale", "Mescola le fotografie.") {
                            TvButton(if (settings.shuffle) "Attivo" else "Disattivo",
                                { onChange(settings.copy(shuffle = !settings.shuffle)) },
                                selected = settings.shuffle, reducedMotion = settings.reducedMotion)
                        }
                    }
                    SettingsCategory.MUSIC -> {
                        Text("Musica", style = MaterialTheme.typography.headlineMedium)
                        SettingRow("Musica di sottofondo", state.musicName ?: "Nessun brano selezionato.") {
                            TvButton("Scegli", onPickMusic, icon = Icons.Rounded.LibraryMusic, reducedMotion = settings.reducedMotion)
                            if (state.musicUri != null) {
                                TvButton("Rimuovi", onRemoveMusic, icon = Icons.Rounded.DeleteOutline, reducedMotion = settings.reducedMotion)
                            }
                        }
                        SettingRow("Volume musica", "Non modifica il volume generale della TV.") {
                            listOf(.15f, .35f, .60f).forEach { volume ->
                                TvButton("${(volume * 100).toInt()}%", { onChange(settings.copy(musicVolume = volume)) },
                                    selected = settings.musicVolume == volume, reducedMotion = settings.reducedMotion)
                            }
                        }
                    }
                    SettingsCategory.ACCESSIBILITY -> {
                        Text("Accessibilità", style = MaterialTheme.typography.headlineMedium)
                        SettingRow("Riduci movimento", "Disattiva gli effetti e la scala animata del focus.") {
                            TvButton(if (settings.reducedMotion) "Attivo" else "Disattivo",
                                { onChange(settings.copy(reducedMotion = !settings.reducedMotion)) },
                                selected = settings.reducedMotion, reducedMotion = settings.reducedMotion)
                        }
                        SettingRow("Orologio", "Mostra l'ora in alto a destra durante la riproduzione.") {
                            TvButton(if (settings.showClock) "Visibile" else "Nascosto",
                                { onChange(settings.copy(showClock = !settings.showClock)) },
                                selected = settings.showClock, reducedMotion = settings.reducedMotion)
                        }
                        SettingRow("Contrasto elevato", "Rende più scuri i pannelli sovrapposti alle fotografie.") {
                            TvButton(if (settings.highContrast) "Attivo" else "Disattivo",
                                { onChange(settings.copy(highContrast = !settings.highContrast)) },
                                selected = settings.highContrast, reducedMotion = settings.reducedMotion)
                        }
                    }
                    SettingsCategory.PRIVACY -> {
                        Text("Account e privacy", style = MaterialTheme.typography.headlineMedium)
                        SettingRow("Dati Google Foto", "Rimuovi account e fotografie memorizzate nella cache privata.") {
                            TvButton("Disconnetti", { confirmDisconnect = true },
                                icon = Icons.Rounded.DeleteOutline, reducedMotion = settings.reducedMotion)
                        }
                        Text(
                            "FotoFesta TV non contiene pubblicità o analytics. Le foto USB non vengono copiate. " +
                                "Le foto scelte da Google sono scaricate in una cache privata temporanea, " +
                                "cancellabile con la disconnessione.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GraphiteSecondary,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}
