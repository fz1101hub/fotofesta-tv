package it.fotofesta.tv.ui

import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.fotofesta.tv.R
import it.fotofesta.tv.tv.BuzzerRound
import it.fotofesta.tv.tv.IpCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private val TEAM_COLORS = listOf(Color(0xFFFF5A5F), Color(0xFF3DA9FC), Color(0xFFFFC145), Color(0xFF4CD787))

/** Tasti che valgono come pulsante: quelli dei gamepad, lettere, numeri, spazio e tasti colorati. */
private fun isBuzzerKey(code: Int): Boolean =
    KeyEvent.isGamepadButton(code) || code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
        code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || code == KeyEvent.KEYCODE_SPACE ||
        code in KeyEvent.KEYCODE_PROG_RED..KeyEvent.KEYCODE_PROG_BLUE

/**
 * Pulsanti quiz: ogni gamepad, tastiera o telecomando collegato alla TV è una squadra. Chi organizza
 * legge la domanda e preme "Via!"; la prima squadra che preme risponde.
 */
@Composable
fun BuzzerScreen(onBack: () -> Unit) {
    val round = remember { BuzzerRound(TEAM_COLORS.size) }
    // BuzzerRound non è osservabile: ogni cambio fa crescere il contatore e ridisegna.
    var revision by remember { mutableIntStateOf(0) }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    fun changed() {
        revision++
    }

    Column(
        Modifier.fillMaxSize().background(Background)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (!isBuzzerKey(native.keyCode)) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) {
                    if (round.press(native.deviceId)) tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 450)
                    changed()
                }
                true
            }
            .padding(horizontal = 48.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        val teams = remember(revision) { round.teams() }
        val winner = remember(revision) { round.winner }
        val armed = remember(revision) { round.armed }
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(
                stringResource(R.string.buzzer_kicker),
                stringResource(R.string.buzzer_title),
                stringResource(R.string.buzzer_subtitle),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(TEAM_COLORS.size) { index ->
                val team = teams.getOrNull(index)
                val color = TEAM_COLORS[index]
                val answering = team != null && team == winner
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(if (answering) color else SurfaceDark, RoundedCornerShape(18.dp))
                        .border(2.dp, if (team != null) color else Outline, RoundedCornerShape(18.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.buzzer_team, index + 1),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (answering) OnAccent else TextPrimary,
                    )
                    if (team == null) {
                        HintText(stringResource(R.string.buzzer_free))
                    } else {
                        Text(
                            quantityStringResource(R.plurals.buzzer_points, round.score(team)),
                            style = MaterialTheme.typography.displaySmall,
                            color = if (answering) OnAccent else color,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            val winnerIndex = winner?.let(teams::indexOf) ?: -1
            Text(
                when {
                    winnerIndex >= 0 -> stringResource(R.string.buzzer_answering, winnerIndex + 1)
                    armed -> stringResource(R.string.buzzer_armed)
                    teams.isEmpty() -> stringResource(R.string.buzzer_join)
                    else -> stringResource(R.string.buzzer_ready)
                },
                style = MaterialTheme.typography.displayMedium,
                color = if (winnerIndex >= 0) TEAM_COLORS[winnerIndex] else TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                stringResource(R.string.buzzer_go),
                { round.arm(); changed() },
                // Sempre attivo: deve avere il fuoco perché la schermata riceva i tasti delle squadre.
                modifier = Modifier.initialFocus(),
                icon = Icons.Rounded.PlayArrow,
            )
            TvButton(stringResource(R.string.buzzer_right), { round.award(); changed() }, icon = Icons.Rounded.Check, enabled = winner != null)
            // Risposta sbagliata: si riapre il turno per tutte le squadre.
            TvButton(stringResource(R.string.buzzer_wrong), { round.arm(); changed() }, icon = Icons.Rounded.Close, enabled = winner != null)
            TvButton(stringResource(R.string.buzzer_reset), { round.reset(); changed() }, icon = Icons.Rounded.Refresh, ghost = true)
        }
    }
}

/**
 * Vecchio telefono come telecamera (app "IP Webcam"): l'immagine dal vivo sulla TV, uno scatto
 * che entra nella festa e l'applausometro con il microfono del telefono.
 */
@Composable
fun CameraScreen(
    camera: IpCamera,
    savedAddress: String,
    partyActive: Boolean,
    onSaveAddress: (String) -> Unit,
    onSnapshot: (ByteArray) -> Unit,
    onBack: () -> Unit,
) {
    var address by remember { mutableStateOf(savedAddress) }
    var connected by remember { mutableStateOf(savedAddress.takeIf { it.isNotBlank() }) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    // Ultimo fotogramma intero, per lo scatto: cambia a ogni immagine e non serve al disegno.
    val lastJpeg = remember { AtomicReference<ByteArray?>(null) }
    var failed by remember { mutableStateOf(false) }
    var meter by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var record by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(connected) {
        val base = connected ?: return@LaunchedEffect
        var lastDecode = 0L
        while (true) {
            try {
                camera.stream(base) { bytes ->
                    lastJpeg.set(bytes)
                    // Una decina di immagini al secondo bastano e lasciano respirare le TV più lente.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastDecode >= FRAME_INTERVAL_MS) {
                        lastDecode = now
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { frame = it.asImageBitmap() }
                        failed = false
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            }
            delay(RETRY_MS)
        }
    }
    LaunchedEffect(connected, meter) {
        val base = connected ?: return@LaunchedEffect
        if (!meter) {
            level = 0f
            return@LaunchedEffect
        }
        while (true) {
            try {
                camera.listen(base) { value ->
                    level = value
                    if (value > record) record = value
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                level = 0f
            }
            delay(RETRY_MS)
        }
    }

    Row(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle(stringResource(R.string.camera_kicker), stringResource(R.string.camera_title), stringResource(R.string.camera_subtitle))
            TvTextField(
                value = address,
                onValueChange = { address = it },
                label = stringResource(R.string.camera_address),
                placeholder = "192.168.1.20:8080",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    onSaveAddress(address)
                    connected = address.trim().takeIf { it.isNotEmpty() }
                }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    stringResource(R.string.camera_connect),
                    {
                        onSaveAddress(address)
                        failed = false
                        connected = address.trim().takeIf { it.isNotEmpty() }
                    },
                    modifier = Modifier.initialFocus(),
                    icon = Icons.Rounded.Videocam,
                )
                TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack, ghost = true)
            }
            TvButton(
                stringResource(R.string.camera_snapshot),
                { lastJpeg.get()?.let(onSnapshot) },
                icon = Icons.Rounded.PhotoCamera,
                enabled = partyActive && frame != null,
            )
            if (!partyActive) HintText(stringResource(R.string.camera_needs_party))
            TvButton(
                stringResource(if (meter) R.string.camera_meter_off else R.string.camera_meter_on),
                { meter = !meter },
                icon = Icons.Rounded.Mic,
                selected = meter,
                enabled = connected != null,
            )
            if (meter) {
                ApplauseMeter(level, record)
                TvButton(stringResource(R.string.camera_meter_reset), { record = 0f }, icon = Icons.Rounded.Refresh, ghost = true)
            }
            HintText(stringResource(R.string.camera_help))
        }
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(SurfaceDark), contentAlignment = Alignment.Center) {
            val image = frame
            when {
                image != null && !failed -> Image(image, contentDescription = stringResource(R.string.camera_title), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                connected == null -> Waiting(stringResource(R.string.camera_empty))
                failed -> Waiting(stringResource(R.string.camera_failed), error = true)
                else -> Waiting(stringResource(R.string.camera_connecting))
            }
        }
    }
}

/** Barra del volume con il record della serata, in percentuale. */
@Composable
private fun ApplauseMeter(level: Float, record: Float) {
    val shown by animateFloatAsState(level, label = "applausi")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(17.dp)).background(SurfaceRaised)) {
            Box(
                Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(if (shown > .8f) Accent else TEAM_COLORS[3], RoundedCornerShape(17.dp)),
            )
        }
        Text(
            stringResource(R.string.camera_meter_value, (shown * 100).roundToInt(), (record * 100).roundToInt()),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            color = TextPrimary,
        )
    }
}

@Composable
private fun Waiting(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = if (error) ErrorText else TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(32.dp),
    )
}

private const val FRAME_INTERVAL_MS = 90L
private const val RETRY_MS = 3_000L
