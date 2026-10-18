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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.SmbLoginState

/**
 * Accesso a un computer o NAS con le cartelle condivise: indirizzo, nome utente e password. Senza
 * nome utente si entra come ospite. Se il computer non sa elencare le condivisioni, se ne scrive il
 * nome.
 */
@Composable
fun SmbLoginScreen(
    state: SmbLoginState,
    onConnect: (address: String, user: String, password: String, share: String) -> Unit,
    onBack: () -> Unit,
) {
    // I campi ripartono dallo stato a ogni nuova apertura (computer diverso o errore di accesso).
    var address by rememberSaveable(state.address) { mutableStateOf(state.address) }
    var user by rememberSaveable(state.address, state.user) { mutableStateOf(state.user) }
    // La password non sopravvive alla ricreazione dell'Activity: non finisce nello stato salvato.
    var password by remember(state.address) { mutableStateOf("") }
    var share by rememberSaveable(state.address) { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val connect = { if (!state.loading) onConnect(address, user, password, if (state.askShare) share else "") }
    val fieldModifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
    Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.smb_kicker),
                state.name ?: stringResource(R.string.smb_title),
                stringResource(R.string.smb_subtitle),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        TvTextField(
            value = address,
            onValueChange = { address = it.take(300) },
            label = stringResource(R.string.smb_address),
            placeholder = stringResource(R.string.smb_address_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = fieldModifier.then(if (state.address.isEmpty()) Modifier.initialFocus("address") else Modifier),
        )
        TvTextField(
            value = user,
            onValueChange = { user = it.take(256) },
            label = stringResource(R.string.smb_user),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = fieldModifier
                .then(if (state.address.isNotEmpty() && !state.askShare) Modifier.initialFocus(state.address) else Modifier),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(
                value = password,
                onValueChange = { password = it.take(256) },
                label = stringResource(R.string.smb_password),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (state.askShare) ImeAction.Next else ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { connect() }),
                modifier = fieldModifier.weight(1f, fill = false),
            )
            // Con il telecomando gli errori di battitura sono facili: la password si può controllare.
            TvButton(
                stringResource(if (showPassword) R.string.smb_hide_password else R.string.smb_show_password),
                { showPassword = !showPassword },
                icon = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            )
        }
        if (state.askShare) {
            Text(stringResource(R.string.smb_share_explain), style = MaterialTheme.typography.bodyLarge, color = TextPrimary, modifier = Modifier.widthIn(max = 760.dp))
            TvTextField(
                value = share,
                onValueChange = { share = it.take(300) },
                label = stringResource(R.string.smb_share),
                placeholder = stringResource(R.string.smb_share_hint),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { connect() }),
                modifier = fieldModifier.initialFocus("share"),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(
                stringResource(R.string.smb_connect),
                { connect() },
                icon = Icons.AutoMirrored.Rounded.Login,
                selected = true,
                enabled = !state.loading,
            )
            if (state.loading) {
                CircularProgressIndicator(color = TextSecondary, modifier = Modifier.size(28.dp))
                Text(stringResource(R.string.smb_connecting), style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            }
        }
        state.errorRes?.let { error ->
            Text(stringResource(error), style = MaterialTheme.typography.bodyLarge, color = ErrorText, modifier = Modifier.widthIn(max = 760.dp))
        }
        Text(stringResource(R.string.smb_note), style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.widthIn(max = 760.dp))
    }
}
