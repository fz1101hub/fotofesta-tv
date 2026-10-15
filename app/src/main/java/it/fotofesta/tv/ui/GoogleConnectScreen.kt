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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.GoogleConnectState

@Composable
fun GoogleConnectScreen(
    state: GoogleConnectState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.google_kicker),
                stringResource(R.string.google_title),
                stringResource(R.string.google_subtitle),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(
                stringResource(R.string.action_back),
                onCancel,
                // Indietro riprende il focus quando sparisce il pulsante principale dello stato precedente.
                modifier = if (state is GoogleConnectState.Error || state is GoogleConnectState.Complete) Modifier
                else Modifier.initialFocus(state::class),
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
            )
        }
        Box(
            Modifier.fillMaxSize().background(NavySoft, RoundedCornerShape(28.dp)).padding(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                GoogleConnectState.Idle -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    CircularProgressIndicator(color = Coral)
                    Text(stringResource(R.string.google_preparing), style = MaterialTheme.typography.titleLarge)
                }
                is GoogleConnectState.WaitingForAuthorization -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                    QrCode(state.verificationUrl, stringResource(R.string.google_qr_authorize))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.google_step_scan), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.google_open_url, state.verificationUrl), style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                        Text(
                            state.userCode,
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold,
                            color = Amber,
                            modifier = Modifier.background(Navy, RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 14.dp),
                        )
                        Text(stringResource(R.string.google_scope_note), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
                is GoogleConnectState.WaitingForPhotos -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                    QrCode(state.pickerUri, stringResource(R.string.google_qr_pick))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.google_step_pick), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.google_pick_instructions), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.google_pick_hint), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        CircularProgressIndicator(color = Cyan)
                    }
                }
                is GoogleConnectState.Downloading -> Column(
                    Modifier.fillMaxWidth(.72f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(stringResource(R.string.google_downloading), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.google_progress, state.completed, state.total), style = MaterialTheme.typography.titleLarge, color = Amber)
                    LinearProgressIndicator(progress = { state.completed.toFloat() / state.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.google_cache_note), style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
                }
                is GoogleConnectState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(stringResource(R.string.google_unavailable), style = MaterialTheme.typography.headlineMedium)
                    Text(state.message, style = MaterialTheme.typography.bodyLarge, color = TextSecondary, textAlign = TextAlign.Center)
                    TvButton(stringResource(R.string.action_retry), onRetry, modifier = Modifier.initialFocus(), icon = Icons.Rounded.Refresh)
                }
                is GoogleConnectState.Complete -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(quantityStringResource(R.plurals.google_photos_ready, state.count), style = MaterialTheme.typography.headlineLarge, color = Cyan)
                    Text(stringResource(R.string.google_complete_body), style = MaterialTheme.typography.bodyLarge, color = TextSecondary, textAlign = TextAlign.Center)
                    TvButton(stringResource(R.string.action_start_slideshow), onPlay, modifier = Modifier.initialFocus(), icon = Icons.Rounded.PlayArrow)
                }
            }
        }
    }
}
