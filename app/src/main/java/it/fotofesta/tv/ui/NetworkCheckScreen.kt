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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.CheckStatus
import it.fotofesta.tv.model.GuestsUiState
import it.fotofesta.tv.model.NetworkCheck

/**
 * Prova della rete della festa, prima che arrivino gli invitati: rete della TV, server della
 * festa, internet, segnale Wi‑Fi e, con il QR, un telefono vero (che scopre le reti "ospiti" che
 * isolano i dispositivi). Accanto a ogni voce un consiglio se qualcosa non va.
 */
@Composable
fun NetworkCheckScreen(check: NetworkCheck, guests: GuestsUiState, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.check_kicker), stringResource(R.string.check_title), stringResource(R.string.check_subtitle), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.check_again), onRetry, icon = Icons.Rounded.Refresh)
            Spacer(Modifier.width(12.dp))
            TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.initialFocus(), icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CheckRow(check.network, stringResource(R.string.check_network), check.networkDetail ?: stringResource(R.string.check_network_fail))
                CheckRow(check.server, stringResource(R.string.check_server), stringResource(if (check.server == CheckStatus.FAIL) R.string.check_server_fail else R.string.check_server_ok))
                CheckRow(check.internet, stringResource(R.string.check_internet), stringResource(if (check.internet == CheckStatus.WARN) R.string.check_internet_warn else R.string.check_internet_ok))
                CheckRow(check.signal, stringResource(R.string.check_signal), listOfNotNull(check.signalDetail, if (check.signal == CheckStatus.WARN) stringResource(R.string.check_signal_warn) else null).joinToString(" · ").ifEmpty { stringResource(R.string.check_signal_cable) })
                CheckRow(check.phone, stringResource(R.string.check_phone), stringResource(if (check.phone == CheckStatus.OK) R.string.check_phone_ok else R.string.check_phone_waiting))
            }
            if (check.phone != CheckStatus.OK) {
                guests.url?.let { url ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QrCode(url, stringResource(R.string.check_phone), Modifier.clip(RoundedCornerShape(16.dp)), size = 200.dp, padding = 14.dp)
                        Text(stringResource(R.string.check_phone_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.width(220.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(status: CheckStatus, title: String, detail: String) {
    val (color, icon) = when (status) {
        CheckStatus.OK -> Color(0xFF4CD787) to Icons.Rounded.Check
        CheckStatus.WARN -> Color(0xFFFFC145) to Icons.Rounded.PriorityHigh
        CheckStatus.FAIL -> ErrorText to Icons.Rounded.Close
        CheckStatus.WAITING -> TextTertiary to Icons.Rounded.HourglassTop
    }
    Row(
        Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(32.dp).background(color.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
