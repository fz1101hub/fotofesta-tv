package it.fotofesta.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.PinPurpose

/**
 * PIN del chiosco: quattro cifre dai tasti numerici del telecomando o dal tastierino sullo
 * schermo. [onSubmit] restituisce false se il PIN è sbagliato: si svuota e si riprova.
 */
@Composable
fun PinDialog(purpose: PinPurpose, onSubmit: (String) -> Boolean, onCancel: () -> Unit) {
    var pin by remember(purpose) { mutableStateOf("") }
    var wrong by remember(purpose) { mutableStateOf(false) }
    fun type(digit: Char) {
        if (pin.length >= PIN_DIGITS) return
        pin += digit
        wrong = false
        if (pin.length == PIN_DIGITS) {
            if (!onSubmit(pin)) {
                wrong = true
                pin = ""
            }
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .82f)).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (val code = event.nativeKeyEvent.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { type('0' + (code - KeyEvent.KEYCODE_0)); true }
                KeyEvent.KEYCODE_DEL -> { pin = pin.dropLast(1); true }
                else -> false
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(560.dp).background(SurfaceDark, RoundedCornerShape(24.dp)).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Kicker(stringResource(R.string.pin_kicker))
            Text(
                stringResource(
                    when (purpose) {
                        PinPurpose.SET -> R.string.pin_set
                        PinPurpose.DISABLE -> R.string.pin_disable
                        PinPurpose.SETTINGS -> R.string.pin_settings
                        PinPurpose.END_PARTY -> R.string.pin_end_party
                        PinPurpose.EXIT -> R.string.pin_exit
                    }
                ),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(PIN_DIGITS) { index ->
                    Box(
                        Modifier.size(22.dp).background(if (index < pin.length) Accent else SurfaceRaised, RoundedCornerShape(11.dp)),
                    )
                }
            }
            if (wrong) Text(stringResource(R.string.pin_wrong), color = ErrorText, style = MaterialTheme.typography.bodyMedium)
            // Tastierino: 1-9, poi cancella, 0 e annulla.
            val rows = listOf("123", "456", "789")
            rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEachIndexed { index, digit ->
                        TvButton(
                            digit.toString(),
                            { type(digit) },
                            modifier = Modifier.width(96.dp).then(if (rowIndex == 0 && index == 0) Modifier.initialFocus() else Modifier),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("⌫", { pin = pin.dropLast(1) }, modifier = Modifier.width(96.dp), ghost = true)
                TvButton("0", { type('0') }, modifier = Modifier.width(96.dp))
                TvButton(stringResource(R.string.pin_cancel), onCancel, modifier = Modifier.width(96.dp), ghost = true)
            }
        }
    }
}

private const val PIN_DIGITS = 4
