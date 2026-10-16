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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.GuestProblem
import it.fotofesta.tv.model.GuestsUiState
import it.fotofesta.tv.model.PhotoItem
import kotlinx.coroutines.delay

/**
 * Festa con le foto degli invitati. Prima di iniziare: come funziona, "Avvia la festa" e le due
 * impostazioni principali. Durante la festa: QR grande a sinistra, contatori, ultime foto e comandi.
 */
@Composable
fun GuestsScreen(
    guests: GuestsUiState,
    recent: List<PhotoItem>,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onPlay: () -> Unit,
    onReview: () -> Unit,
    onAwards: () -> Unit,
    onApproval: (Boolean) -> Unit,
    onWifi: () -> Unit,
) {
    if (guests.active) {
        PartyInProgress(guests, recent, onEnd, onPlay, onReview, onAwards)
    } else {
        PartyIntro(guests, onStart, onApproval, onWifi)
    }
}

@Composable
private fun PartyIntro(
    guests: GuestsUiState,
    onStart: () -> Unit,
    onApproval: (Boolean) -> Unit,
    onWifi: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp)) {
        BrandMark()
        Row(Modifier.fillMaxSize().padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionTitle(stringResource(R.string.guests_kicker), stringResource(R.string.guests_title), large = true)
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Step(1, stringResource(R.string.guests_step_1))
                    Step(2, stringResource(R.string.guests_step_2))
                    Step(3, stringResource(R.string.guests_step_3))
                }
                Row {
                    TvButton(
                        stringResource(R.string.home_start_party),
                        onStart,
                        modifier = Modifier.initialFocus(),
                        icon = Icons.Rounded.Groups,
                    )
                }
                Text(stringResource(R.string.guests_same_network), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            Column(Modifier.width(380.dp).padding(top = 34.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsItem(
                    stringResource(R.string.guests_approval),
                    { onApproval(!guests.approval) },
                    subtitle = stringResource(R.string.guests_approval_body),
                    checked = guests.approval,
                )
                SettingsItem(
                    stringResource(R.string.guests_wifi),
                    onWifi,
                    subtitle = stringResource(R.string.guests_wifi_body),
                    value = guests.wifiName ?: stringResource(R.string.settings_wifi_none),
                )
                Text(
                    stringResource(R.string.guests_intro),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

/** Passo numerato di come funziona la festa. */
@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).background(SurfaceRaised, CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold), color = TextPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}

@Composable
private fun PartyInProgress(
    guests: GuestsUiState,
    recent: List<PhotoItem>,
    onEnd: () -> Unit,
    onPlay: () -> Unit,
    onReview: () -> Unit,
    onAwards: () -> Unit,
) {
    Row(Modifier.fillMaxSize().background(Background)) {
        Column(
            Modifier.width(360.dp).fillMaxHeight().background(SurfaceDark).padding(horizontal = 24.dp, vertical = 27.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            SendPanel(guests)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier.fillMaxSize().padding(start = 44.dp, end = 48.dp, top = 60.dp, bottom = 27.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Kicker(stringResource(R.string.home_guests_title))
                Text(stringResource(R.string.guests_party_started), style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Stat(guests.shown, quantityStringResource(R.plurals.guests_stat_received, guests.shown), highlight = false)
                    if (guests.approval || guests.pending.isNotEmpty()) {
                        Stat(guests.pending.size, quantityStringResource(R.plurals.guests_stat_pending, guests.pending.size), highlight = guests.pending.isNotEmpty())
                    }
                }
                if (recent.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recent.forEach { photo ->
                            PhotoContent(
                                photo,
                                FitMode.CROP,
                                Modifier.size(width = 92.dp, height = 62.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceDark),
                                contentDescription = null,
                                placeholder = false,
                            )
                        }
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        stringResource(R.string.action_start_slideshow),
                        onPlay,
                        modifier = Modifier.initialFocus(),
                        icon = Icons.Rounded.PlayArrow,
                    )
                    if (guests.pending.isNotEmpty()) {
                        TvButton(quantityStringResource(R.plurals.guests_approve_photos, guests.pending.size), onReview)
                    }
                    if (guests.shown > 0) {
                        TvButton(stringResource(R.string.guests_awards), onAwards, icon = Icons.Rounded.EmojiEvents)
                    }
                }
            }
            EndPartyButton(onEnd, Modifier.align(Alignment.TopEnd).padding(horizontal = 48.dp, vertical = 27.dp))
        }
    }
}

/** Numero grande con la sua etichetta; [highlight] lo colora con l'accento (foto da approvare). */
@Composable
private fun Stat(value: Int, label: String, highlight: Boolean) {
    Column(
        Modifier.widthIn(min = 150.dp).background(SurfaceDark, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, fontFeatureSettings = "tnum"),
            color = if (highlight) Accent else TextPrimary,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

/** QR per mandare le foto (con quello della Wi‑Fi, se impostata), oppure perché la TV non è pronta. */
@Composable
private fun SendPanel(guests: GuestsUiState) {
    val url = guests.url
    if (url == null) {
        WaitingOrProblem(guests, TextAlign.Center)
        return
    }
    val wifi = guests.wifiQr
    if (wifi != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            QrWithLabel(wifi, stringResource(R.string.guests_qr_wifi), stringResource(R.string.guests_qr_step_wifi), 140.dp)
            QrWithLabel(url, stringResource(R.string.guests_qr_send), stringResource(R.string.guests_qr_step_send), 140.dp)
        }
    } else {
        QrCode(url, stringResource(R.string.guests_qr_send), Modifier.clip(RoundedCornerShape(16.dp)), size = 198.dp, padding = 16.dp)
    }
    Text(
        stringResource(R.string.guests_send_title),
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
        color = TextPrimary,
        textAlign = TextAlign.Center,
    )
    Text(
        guests.wifiName?.let { stringResource(R.string.guests_send_body_network, it) } ?: stringResource(R.string.guests_send_body),
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 280.dp),
    )
}

@Composable
private fun QrWithLabel(value: String, description: String, label: String, size: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QrCode(value, description, Modifier.clip(RoundedCornerShape(12.dp)), size = size - 20.dp, padding = 10.dp)
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = TextPrimary, textAlign = TextAlign.Center)
    }
}

/** La TV si sta collegando alla rete, oppure il motivo per cui non può ricevere foto. */
@Composable
private fun WaitingOrProblem(guests: GuestsUiState, align: TextAlign, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val problem = guests.problem
        if (problem == null) {
            CircularProgressIndicator(color = TextSecondary)
            Text(stringResource(R.string.guests_waiting_network), style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = align)
        } else {
            Text(problemText(problem), style = MaterialTheme.typography.bodyLarge, color = ErrorText, textAlign = align)
        }
    }
}

/** Cancellare tutte le foto della festa chiede una seconda pressione entro pochi secondi. */
@Composable
private fun EndPartyButton(onEnd: () -> Unit, modifier: Modifier = Modifier) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4_000)
            armed = false
        }
    }
    TvButton(
        stringResource(if (armed) R.string.guests_end_confirm else R.string.guests_end_short),
        {
            if (armed) {
                armed = false
                onEnd()
            } else {
                armed = true
            }
        },
        modifier = modifier,
        selected = armed,
        ghost = !armed,
    )
}

@Composable
private fun problemText(problem: GuestProblem): String = stringResource(
    when (problem) {
        GuestProblem.NO_NETWORK -> R.string.guests_no_network
        GuestProblem.SERVER_FAILED -> R.string.guests_server_failed
    }
)

/**
 * Attesa della prima foto nello slideshow della festa: QR grande al centro, oppure il motivo per
 * cui la TV non può ancora ricevere foto.
 */
@Composable
fun WaitingForGuests(guests: GuestsUiState, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 640.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(R.string.guests_waiting_title), style = MaterialTheme.typography.headlineLarge, color = TextPrimary, textAlign = TextAlign.Center)
        val url = guests.url
        if (url != null) {
            QrCode(url, stringResource(R.string.guests_qr_send), Modifier.clip(RoundedCornerShape(16.dp)), size = 220.dp, padding = 16.dp)
            Text(
                stringResource(R.string.guests_waiting_body),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        } else {
            WaitingOrProblem(guests, TextAlign.Center)
        }
    }
}

/** QR piccolo nell'angolo dello slideshow, o l'avviso se la festa non riesce a partire. */
@Composable
fun SlideshowGuestQr(guests: GuestsUiState, modifier: Modifier = Modifier) {
    val url = guests.url
    Row(
        modifier.background(Background.copy(alpha = .78f), RoundedCornerShape(12.dp)).padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (url != null) {
            QrCode(url, stringResource(R.string.guests_qr_send), Modifier.clip(RoundedCornerShape(6.dp)), size = 80.dp, padding = 5.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(R.string.guests_slideshow_cta), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                Text(stringResource(R.string.slideshow_qr_body), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = TextSecondary)
            }
        } else {
            val problem = guests.problem
            if (problem == null) {
                CircularProgressIndicator(Modifier.size(20.dp), color = TextSecondary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.guests_waiting_network), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            } else {
                Text(
                    problemText(problem),
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorText,
                    modifier = Modifier.widthIn(max = 320.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
    Row(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        val photo = pending.firstOrNull()
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
            if (photo != null) {
                PhotoContent(photo, FitMode.CONTAIN, Modifier.fillMaxSize())
            }
        }
        Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(
                stringResource(R.string.review_kicker),
                stringResource(R.string.review_title),
                quantityStringResource(R.plurals.guests_pending, pending.size),
            )
            if (photo == null) {
                HintText(stringResource(R.string.review_empty))
                TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.initialFocus())
                return@Column
            }
            Text(photo.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
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
                    ghost = true,
                )
            }
        }
    }
}

/** Nome e password della Wi‑Fi per il QR che fa entrare gli invitati nella rete di casa. */
@Composable
fun GuestWifiScreen(
    initialName: String?,
    initialPassword: String?,
    onSave: (ssid: String, password: String) -> Boolean,
) {
    var ssid by rememberSaveable { mutableStateOf(initialName.orEmpty()) }
    // Anche la password è già compilata: con il campo vuoto la rete diventerebbe "aperta".
    var password by rememberSaveable { mutableStateOf(initialPassword.orEmpty()) }
    Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BrandMark()
        SectionTitle(
            stringResource(R.string.wifi_kicker),
            stringResource(R.string.wifi_title),
            stringResource(R.string.wifi_subtitle),
            modifier = Modifier.padding(top = 18.dp).widthIn(max = 640.dp),
        )
        TvTextField(
            value = ssid,
            onValueChange = { ssid = it.take(64) },
            label = stringResource(R.string.wifi_name),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().initialFocus(),
        )
        // La password resta visibile: con il telecomando gli errori di battitura sono facili, e il QR
        // la mostra comunque a chi lo inquadra.
        TvTextField(
            value = password,
            onValueChange = { password = it.take(64) },
            label = stringResource(R.string.wifi_password),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        )
        Row {
            TvButton(stringResource(R.string.wifi_save), { onSave(ssid, password) }, icon = Icons.Rounded.Save)
        }
    }
}
