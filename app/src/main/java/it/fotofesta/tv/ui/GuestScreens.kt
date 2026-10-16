package it.fotofesta.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.GuestProblem
import it.fotofesta.tv.model.GuestsUiState
import it.fotofesta.tv.model.PhotoItem
import kotlinx.coroutines.delay

/** Festa con le foto degli invitati: QR da inquadrare, foto da approvare e impostazioni. */
@Composable
fun GuestsScreen(
    guests: GuestsUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onPlay: () -> Unit,
    onReview: () -> Unit,
    onApproval: (Boolean) -> Unit,
    onQrInSlideshow: (Boolean) -> Unit,
    onWifi: () -> Unit,
    onRemoveWifi: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.guests_kicker),
                stringResource(R.string.guests_title),
                stringResource(R.string.guests_subtitle),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
            if (guests.active) {
                Spacer(Modifier.width(12.dp))
                TvButton(
                    stringResource(R.string.action_start_slideshow),
                    onPlay,
                    modifier = Modifier.initialFocus(),
                    icon = Icons.Rounded.PlayArrow,
                )
            }
        }
        if (!guests.active) {
            Column(
                Modifier.fillMaxWidth().background(NavySoft, RoundedCornerShape(24.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(stringResource(R.string.guests_intro), style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                Text(stringResource(R.string.guests_same_network), style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                TvButton(
                    stringResource(R.string.guests_start),
                    onStart,
                    modifier = Modifier.initialFocus(),
                    icon = Icons.Rounded.Groups,
                )
            }
            return@Column
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val qrSize = if (guests.wifiQr != null) 170.dp else 220.dp
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                guests.wifiQr?.let { payload ->
                    QrCard(
                        payload,
                        stringResource(R.string.guests_qr_wifi),
                        stringResource(R.string.guests_scan_wifi, guests.wifiName.orEmpty()),
                        qrSize,
                    )
                }
                SendQr(guests, qrSize)
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        quantityStringResource(R.plurals.guests_shown, guests.shown),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (guests.pending.isNotEmpty()) {
                        TvButton(
                            quantityStringResource(R.plurals.guests_pending, guests.pending.size) + " · " +
                                stringResource(R.string.guests_review),
                            onReview,
                            selected = true,
                        )
                    }
                }
                SettingRow(stringResource(R.string.guests_approval), stringResource(R.string.guests_approval_body)) {
                    ToggleButton(guests.approval) { onApproval(!guests.approval) }
                }
                SettingRow(stringResource(R.string.guests_qr_in_slideshow), stringResource(R.string.guests_qr_in_slideshow_body)) {
                    ToggleButton(guests.qrInSlideshow) { onQrInSlideshow(!guests.qrInSlideshow) }
                }
                SettingRow(stringResource(R.string.guests_wifi), guests.wifiName ?: stringResource(R.string.guests_wifi_body)) {
                    TvButton(
                        stringResource(if (guests.wifiName == null) R.string.guests_wifi_set else R.string.guests_wifi_change),
                        onWifi,
                        icon = Icons.Rounded.Wifi,
                    )
                    if (guests.wifiName != null) {
                        TvButton(stringResource(R.string.guests_wifi_remove), onRemoveWifi, icon = Icons.Rounded.DeleteOutline)
                    }
                }
                EndPartyButton(onEnd)
                Text(stringResource(R.string.guests_intro), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun ToggleButton(on: Boolean, onClick: () -> Unit) {
    TvButton(
        stringResource(if (on) R.string.settings_on else R.string.settings_off),
        onClick,
        selected = on,
    )
}

/** Cancellare tutte le foto della festa chiede una seconda pressione entro pochi secondi. */
@Composable
private fun EndPartyButton(onEnd: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4_000)
            armed = false
        }
    }
    TvButton(
        stringResource(if (armed) R.string.guests_end_confirm else R.string.guests_end),
        {
            if (armed) {
                armed = false
                onEnd()
            } else {
                armed = true
            }
        },
        icon = Icons.Rounded.DeleteOutline,
        selected = armed,
    )
}

/** QR per mandare le foto, oppure il motivo per cui la TV non può ancora riceverle. */
@Composable
private fun SendQr(guests: GuestsUiState, size: Dp) {
    val url = guests.url
    if (url != null) {
        QrCard(url, stringResource(R.string.guests_qr_send), stringResource(R.string.guests_scan_send), size, detail = url)
        return
    }
    Column(
        Modifier.width(size + 24.dp).background(NavySoft, RoundedCornerShape(20.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (guests.problem) {
            null -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.guests_waiting_network), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            else -> Text(
                problemText(guests.problem),
                style = MaterialTheme.typography.bodyMedium,
                color = Amber,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun problemText(problem: GuestProblem): String = stringResource(
    when (problem) {
        GuestProblem.NO_NETWORK -> R.string.guests_no_network
        GuestProblem.SERVER_FAILED -> R.string.guests_server_failed
    }
)

@Composable
private fun QrCard(value: String, description: String, caption: String, size: Dp, detail: String? = null) {
    Column(
        Modifier.width(size + 24.dp + 32.dp).background(NavySoft, RoundedCornerShape(20.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QrCode(value, description, size = size)
        Text(caption, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Attesa della prima foto nello slideshow della festa: QR grande al centro, oppure il motivo per
 * cui la TV non può ancora ricevere foto.
 */
@Composable
fun WaitingForGuests(guests: GuestsUiState, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 760.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.guests_waiting_title), style = MaterialTheme.typography.headlineLarge)
        val url = guests.url
        if (url != null) {
            QrCode(url, stringResource(R.string.guests_qr_send), size = 240.dp)
            Text(
                stringResource(R.string.guests_waiting_body),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                guests.problem?.let { problemText(it) } ?: stringResource(R.string.guests_waiting_network),
                style = MaterialTheme.typography.bodyLarge,
                color = Amber,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** QR piccolo nell'angolo dello slideshow. */
@Composable
fun SlideshowGuestQr(url: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.Black.copy(alpha = .55f), RoundedCornerShape(16.dp)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        QrCode(url, stringResource(R.string.guests_qr_send), size = 120.dp, padding = 10.dp)
        Text(stringResource(R.string.guests_slideshow_cta), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Foto in attesa: si mostrano una alla volta, la più vecchia per prima. */
@Composable
fun GuestReviewScreen(
    pending: List<PhotoItem>,
    onBack: () -> Unit,
    onShow: (PhotoItem) -> Unit,
    onDiscard: (PhotoItem) -> Unit,
    onApproveAll: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.review_kicker),
                stringResource(R.string.review_title),
                quantityStringResource(R.plurals.guests_pending, pending.size),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(
                stringResource(R.string.action_back),
                onBack,
                modifier = if (pending.isEmpty()) Modifier.initialFocus() else Modifier,
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
            )
        }
        val photo = pending.firstOrNull()
        if (photo == null) {
            Text(stringResource(R.string.review_empty), style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            return@Column
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black, RoundedCornerShape(20.dp))) {
                PhotoContent(photo, FitMode.CONTAIN, Modifier.fillMaxSize())
            }
            Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(photo.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // Il fuoco resta su "Mostra" anche quando arriva la foto successiva.
                TvButton(
                    stringResource(R.string.review_show),
                    { onShow(photo) },
                    modifier = Modifier.fillMaxWidth().initialFocus(),
                    icon = Icons.Rounded.Check,
                )
                TvButton(
                    stringResource(R.string.review_discard),
                    { onDiscard(photo) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Close,
                )
                if (pending.size > 1) {
                    TvButton(
                        stringResource(R.string.review_approve_all),
                        onApproveAll,
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.DoneAll,
                    )
                }
            }
        }
    }
}

/** Nome e password della Wi‑Fi per il QR che fa entrare gli invitati nella rete di casa. */
@Composable
fun GuestWifiScreen(
    initialName: String?,
    initialPassword: String?,
    onBack: () -> Unit,
    onSave: (ssid: String, password: String) -> Boolean,
) {
    var ssid by rememberSaveable { mutableStateOf(initialName.orEmpty()) }
    // Anche la password è già compilata: con il campo vuoto la rete diventerebbe "aperta".
    var password by rememberSaveable { mutableStateOf(initialPassword.orEmpty()) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 30.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.wifi_kicker),
                stringResource(R.string.wifi_title),
                stringResource(R.string.wifi_subtitle),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber,
            unfocusedBorderColor = Color(0xFF55617C),
            focusedLabelColor = Amber,
            cursorColor = Amber,
        )
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it.take(64) },
            label = { Text(stringResource(R.string.wifi_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            colors = fieldColors,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().initialFocus(),
        )
        // La password resta visibile: con il telecomando gli errori di battitura sono facili, e il QR
        // la mostra comunque a chi lo inquadra.
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(64) },
            label = { Text(stringResource(R.string.wifi_password)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            colors = fieldColors,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(stringResource(R.string.wifi_save), { onSave(ssid, password) }, icon = Icons.Rounded.Save)
        }
        Spacer(Modifier.size(8.dp))
    }
}
