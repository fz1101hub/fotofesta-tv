package it.fotofesta.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.guests.PhoneGame
import kotlinx.coroutines.delay

/** Gioco con i telefoni durante la festa: [url] è l'indirizzo della pagina degli invitati, nel QR. */
class PhonePlay(val game: PhoneGame, val url: String)

private data class PhoneStatus(val answers: Int = 0, val leaderboard: List<PhoneGame.Score> = emptyList())

/**
 * Chiude la domanda sui telefoni quando si esce dal gioco o si torna al telecomando, così la pagina
 * degli invitati non resta su una domanda a cui nessuno guarda più.
 */
@Composable
fun StopPhoneGameOnExit(play: PhonePlay?, active: Boolean) {
    DisposableEffect(play, active) {
        onDispose { if (active) play?.game?.stop() }
    }
}

/**
 * Pannello del gioco con i telefoni: QR della festa, quante risposte sono arrivate e, dopo la
 * risposta giusta, la classifica. Si aggiorna da solo mentre gli invitati rispondono.
 */
@Composable
fun PhonePlayPanel(play: PhonePlay, revealed: Boolean, modifier: Modifier = Modifier) {
    val status by produceState(PhoneStatus(), play.game) {
        while (true) {
            value = PhoneStatus(play.game.answerCount(), play.game.leaderboard())
            delay(500)
        }
    }
    Column(
        modifier.background(SurfaceDark, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            QrCode(play.url, stringResource(R.string.phone_scan), size = 96.dp, padding = 6.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.phone_title), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(stringResource(R.string.phone_scan), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        Text(
            quantityStringResource(R.plurals.phone_answers, status.answers),
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
        )
        if (revealed && status.leaderboard.isNotEmpty()) {
            Kicker(stringResource(R.string.phone_leaderboard))
            status.leaderboard.forEachIndexed { index, score ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${index + 1}. ${score.name}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.padding(4.dp))
                    Text(quantityStringResource(R.plurals.phone_points, score.points), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }
        }
    }
}

/** Risposta mostrata sulla TV durante il gioco con i telefoni; [highlighted] è quella giusta. */
@Composable
fun ChoiceLabel(text: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth()
            .background(if (highlighted) Accent else SurfaceRaised, RoundedCornerShape(50))
            .padding(horizontal = 22.dp, vertical = 11.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (highlighted) OnAccent else TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
