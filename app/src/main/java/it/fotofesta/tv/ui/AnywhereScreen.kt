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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R

/**
 * Festa ovunque: in spiaggia, in campeggio o in giardino, senza la Wi‑Fi di casa. La TV crea la sua
 * rete e passa a proiettore e modalità leggera; a sinistra il kit che serve.
 */
@Composable
fun AnywhereScreen(
    hotspot: Boolean,
    projector: Boolean,
    light: Boolean,
    onHotspot: () -> Unit,
    onProjector: () -> Unit,
    onLight: () -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.anywhere_kicker),
                stringResource(R.string.anywhere_title),
                stringResource(R.string.anywhere_subtitle),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Kicker(stringResource(R.string.anywhere_kit))
                KitItem(Icons.Rounded.Tv, stringResource(R.string.anywhere_kit_stick))
                KitItem(Icons.Rounded.BatteryChargingFull, stringResource(R.string.anywhere_kit_power))
                KitItem(Icons.Rounded.Videocam, stringResource(R.string.anywhere_kit_projector))
                KitItem(Icons.Rounded.Headphones, stringResource(R.string.anywhere_kit_music))
            }
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsItem(stringResource(R.string.guests_hotspot), onHotspot, subtitle = stringResource(R.string.anywhere_hotspot_body), checked = hotspot)
                SettingsItem(stringResource(R.string.settings_projector), onProjector, subtitle = stringResource(R.string.settings_projector_body), checked = projector)
                SettingsItem(stringResource(R.string.settings_light_mode), onLight, subtitle = stringResource(R.string.anywhere_light_body), checked = light)
                Spacer(Modifier.size(6.dp))
                TvButton(stringResource(R.string.anywhere_go), onGo, modifier = Modifier.initialFocus(), icon = Icons.Rounded.Luggage)
            }
        }
    }
}

@Composable
private fun KitItem(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(SurfaceRaised, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}
