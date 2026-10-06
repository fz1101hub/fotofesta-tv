package it.fotofesta.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.TransitionType

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
    Column(
        Modifier.fillMaxSize().padding(horizontal = 46.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Personalizza", "Impostazioni slideshow", "Le modifiche vengono salvate solo su questa TV.")
            }
            TvButton("Indietro", onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
            Spacer(Modifier.size(12.dp))
            TvButton("Avvia", onPlay, icon = Icons.Rounded.PlayArrow)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingRow("Durata di ogni foto", "Tempo prima della diapositiva successiva.") {
                listOf(3, 5, 8, 12, 20).forEach { seconds ->
                    TvButton("${seconds}s", { onChange(settings.copy(intervalSeconds = seconds)) }, selected = settings.intervalSeconds == seconds)
                }
            }
            SettingRow("Transizione", "Scegli un movimento sobrio tra le immagini.") {
                TransitionType.entries.forEach { transition ->
                    TvButton(transition.label, { onChange(settings.copy(transition = transition)) }, selected = settings.transition == transition)
                }
            }
            SettingRow("Adattamento foto", "Mostra tutta la foto oppure riempi lo schermo tagliando i bordi.") {
                FitMode.entries.forEach { fit ->
                    TvButton(fit.label, { onChange(settings.copy(fitMode = fit)) }, selected = settings.fitMode == fit)
                }
            }
            SettingRow("Ordine casuale", "Evita la sequenza alfabetica della cartella.") {
                TvButton(if (settings.shuffle) "Attivo" else "Disattivo", { onChange(settings.copy(shuffle = !settings.shuffle)) }, selected = settings.shuffle)
            }
            SettingRow("Riduci movimento", "Disattiva le animazioni, utile per sensibilità al movimento.") {
                TvButton(if (settings.reducedMotion) "Attivo" else "Disattivo", { onChange(settings.copy(reducedMotion = !settings.reducedMotion)) }, selected = settings.reducedMotion)
            }
            SettingRow("Orologio", "Mostra l’ora nell’angolo in alto a destra.") {
                TvButton(if (settings.showClock) "Visibile" else "Nascosto", { onChange(settings.copy(showClock = !settings.showClock)) }, selected = settings.showClock)
            }
            SettingRow("Musica di sottofondo", state.musicName ?: "Nessun brano selezionato. Sono supportati i formati riconosciuti dalla TV.") {
                TvButton("Scegli brano", onPickMusic, icon = Icons.Rounded.LibraryMusic)
                if (state.musicUri != null) TvButton("Rimuovi", onRemoveMusic, icon = Icons.Rounded.DeleteOutline)
            }
            SettingRow("Volume musica", "Regola il sottofondo senza modificare il volume generale della TV.") {
                listOf(.15f, .35f, .60f).forEach { volume ->
                    TvButton("${(volume * 100).toInt()}%", { onChange(settings.copy(musicVolume = volume)) }, selected = settings.musicVolume == volume)
                }
            }
            SettingRow("Dati Google Foto", "Rimuove token cifrati e cache privata delle foto dalla TV.") {
                TvButton("Disconnetti e cancella", onDisconnectGoogle, icon = Icons.Rounded.DeleteOutline)
            }
            Text(
                "Privacy: FotoFesta TV non contiene pubblicità o analytics. Le foto USB non vengono copiate. Le foto scelte da Google sono scaricate automaticamente in una cache privata temporanea per evitare la scadenza degli URL e vengono eliminate con ‘Disconnetti e cancella’ o dal sistema.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
