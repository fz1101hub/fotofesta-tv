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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.model.GoogleConnectState

@Composable
fun GoogleConnectScreen(
    state: GoogleConnectState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(46.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Aggiungi le tue foto", "Collega Google Foto", "Usa il telefono per scegliere soltanto le fotografie da mostrare.")
            Spacer(Modifier.weight(1f))
            TvButton("Indietro", onCancel, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Box(
            Modifier.fillMaxSize().background(NavySoft, RoundedCornerShape(28.dp)).padding(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                GoogleConnectState.Idle -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    CircularProgressIndicator(color = Coral)
                    Text("Preparazione del collegamento…", style = MaterialTheme.typography.titleLarge)
                }
                is GoogleConnectState.WaitingForAuthorization -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                    QrCode(state.verificationUrl, "Codice QR per autorizzare Google Foto")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("PASSAGGIO 1 DI 3  ·  COLLEGA IL TELEFONO", color = GraphiteSecondary, style = MaterialTheme.typography.labelLarge)
                        Text("Scansiona il QR", style = MaterialTheme.typography.headlineMedium)
                        Text("Apri ${state.verificationUrl} sul telefono e inserisci questo codice:", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                        Text(
                            state.userCode,
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold,
                            color = Amber,
                            modifier = Modifier.background(Navy, RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 14.dp),
                        )
                        Text("Richiediamo soltanto l’accesso alle foto che sceglierai. Il codice scade automaticamente.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
                is GoogleConnectState.WaitingForPhotos -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                    QrCode(state.pickerUri, "Codice QR per scegliere le foto")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("PASSAGGIO 2 DI 3  ·  SCEGLI LE FOTO", color = GraphiteSecondary, style = MaterialTheme.typography.labelLarge)
                        Text("Seleziona i tuoi ricordi", style = MaterialTheme.typography.headlineMedium)
                        Text("Scansiona il QR, seleziona le immagini in Google Foto e premi Fine.", style = MaterialTheme.typography.bodyLarge)
                        Text("Puoi cercare per data, luogo o nome dell’album. La TV rileverà automaticamente quando hai finito.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        CircularProgressIndicator(color = Cyan)
                    }
                }
                is GoogleConnectState.Downloading -> Column(
                    Modifier.fillMaxWidth(.72f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("PASSAGGIO 3 DI 3  ·  PREPARAZIONE", color = GraphiteSecondary, style = MaterialTheme.typography.labelLarge)
                    Text("Le tue fotografie sono quasi pronte", style = MaterialTheme.typography.headlineMedium)
                    Text("${state.completed} di ${state.total}", style = MaterialTheme.typography.titleLarge, color = Amber)
                    LinearProgressIndicator(progress = { state.completed.toFloat() / state.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text("La cache privata evita interruzioni durante lo slideshow. Non viene aggiunto nulla alla galleria della TV.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
                }
                is GoogleConnectState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Non è stato possibile completare il collegamento", style = MaterialTheme.typography.headlineMedium)
                    Text(state.message, style = MaterialTheme.typography.bodyLarge, color = TextSecondary, textAlign = TextAlign.Center)
                    TvButton("Riprova", onRetry, icon = Icons.Rounded.Refresh)
                }
                is GoogleConnectState.Complete -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("${state.count} foto pronte", style = MaterialTheme.typography.headlineLarge, color = GraphiteWhite)
                    Text("Puoi avviare subito lo slideshow o tornare indietro per cambiare musica e transizioni.", style = MaterialTheme.typography.bodyLarge, color = TextSecondary, textAlign = TextAlign.Center)
                    TvButton("Avvia slideshow", onPlay, icon = Icons.Rounded.PlayArrow, primary = true)
                }
            }
        }
    }
}
