package it.fotofesta.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.ICloudLinkState

/**
 * Collegamento di un album condiviso di iCloud con "Sito web pubblico": il link si incolla dal
 * telefono inquadrando il QR, oppure il codice si scrive con il telecomando. Poi l'anteprima
 * dell'album e "Usa questo album".
 */
@Composable
fun ICloudLinkScreen(
    state: ICloudLinkState,
    onSubmit: (String) -> Unit,
    onUse: () -> Unit,
    onBack: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.icloud_kicker),
                stringResource(R.string.icloud_title),
                stringResource(R.string.icloud_subtitle),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // QR per il telefono: si incolla il link lì, invece di scriverlo con il telecomando.
            Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val url = state.pairingUrl
                if (url != null) {
                    QrCode(url, stringResource(R.string.icloud_qr), size = 200.dp)
                    Text(stringResource(R.string.icloud_qr), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                } else {
                    Text(stringResource(R.string.icloud_no_network), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.icloud_steps), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvTextField(
                        value = input,
                        onValueChange = { input = it.take(300) },
                        label = stringResource(R.string.icloud_field),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onSubmit(input) }),
                        modifier = Modifier.widthIn(max = 460.dp).weight(1f, fill = false)
                            .then(if (state.token == null) Modifier.initialFocus("field") else Modifier),
                    )
                    TvButton(stringResource(R.string.icloud_open), { onSubmit(input) }, icon = Icons.Rounded.Search, enabled = !state.loading)
                }
                when {
                    state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = TextSecondary, modifier = Modifier.size(28.dp))
                        Text(stringResource(R.string.icloud_loading), style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                    }
                    state.errorRes != null ->
                        Text(stringResource(state.errorRes), style = MaterialTheme.typography.bodyLarge, color = ErrorText)
                    state.token != null -> Column(
                        Modifier.background(SurfaceDark, RoundedCornerShape(16.dp)).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(state.name.ifBlank { stringResource(R.string.home_icloud_title) }, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        val counts = listOfNotNull(
                            quantityStringResource(R.plurals.icloud_photo_count, state.photos).takeIf { state.photos > 0 },
                            quantityStringResource(R.plurals.icloud_video_count, state.videos).takeIf { state.videos > 0 },
                            state.owner?.let { stringResource(R.string.icloud_album_by, it) },
                        )
                        Text(counts.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                        TvButton(
                            stringResource(R.string.icloud_use),
                            onUse,
                            modifier = Modifier.padding(top = 6.dp).initialFocus(state.token),
                            icon = Icons.Rounded.CheckCircle,
                            selected = true,
                        )
                    }
                }
                Text(stringResource(R.string.icloud_note), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}
