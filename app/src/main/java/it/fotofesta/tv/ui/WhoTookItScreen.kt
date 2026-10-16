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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.WhoTookIt

/** "Chi l'ha scattata?": una foto della festa e i nomi degli invitati; si indovina chi l'ha mandata. */
@Composable
fun WhoTookItScreen(photos: List<PhotoItem>, phones: PhonePlay?, onBack: () -> Unit) {
    if (!WhoTookIt.canPlay(photos)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            SectionTitle(
                stringResource(R.string.games_who_title),
                stringResource(R.string.who_unavailable_title),
                stringResource(R.string.games_who_unavailable),
            )
            TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.initialFocus(), icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        return
    }
    var roundNumber by remember { mutableIntStateOf(0) }
    // Non è uno State osservato: serve solo a non riproporre subito la stessa foto.
    val previousPhotoId = remember { arrayOfNulls<String>(1) }
    val round = remember(roundNumber, photos) {
        // canPlay è vero, quindi c'è sempre una domanda.
        WhoTookIt.round(photos, previousPhotoId[0])!!.also { previousPhotoId[0] = it.photo.id }
    }
    var chosen by remember(roundNumber) { mutableStateOf<String?>(null) }
    var score by remember { mutableIntStateOf(0) }
    var played by remember { mutableIntStateOf(0) }
    // Con i telefoni rispondono gli invitati; il telecomando mostra la risposta e passa alla foto dopo.
    var phoneMode by remember { mutableStateOf(false) }
    var revealed by remember(roundNumber, phoneMode) { mutableStateOf(false) }
    val nextFocus = remember { FocusRequester() }
    // Dopo la risposta i nomi si disattivano: il fuoco passa a "Prossima foto".
    LaunchedEffect(chosen, revealed) { if (chosen != null || revealed) runCatching { nextFocus.requestFocus() } }
    val question = stringResource(R.string.who_question)
    LaunchedEffect(round, phoneMode) {
        if (phoneMode && phones != null) phones.game.ask(question, round.choices, round.choices.indexOf(round.answer))
    }
    StopPhoneGameOnExit(phones, phoneMode)

    val answered = chosen
    val title = when {
        phoneMode -> if (revealed) stringResource(R.string.phone_reveal_who, round.answer) else question
        answered == null -> question
        WhoTookIt.isAnswer(round, answered) -> stringResource(R.string.who_correct, round.answer)
        else -> stringResource(R.string.who_wrong, round.answer)
    }

    Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                if (phoneMode) stringResource(R.string.games_who_title) else stringResource(R.string.who_kicker, score, played),
                title,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            if (phones != null) {
                TvButton(
                    stringResource(if (phoneMode) R.string.phone_mode_remote else R.string.phone_mode_phones),
                    {
                        phoneMode = !phoneMode
                        if (phoneMode) phones.game.resetScores()
                    },
                    icon = if (phoneMode) Icons.Rounded.SportsEsports else Icons.Rounded.Smartphone,
                )
                Spacer(Modifier.width(12.dp))
            }
            if (phoneMode && phones != null && !revealed) {
                TvButton(
                    stringResource(R.string.phone_reveal),
                    {
                        phones.game.reveal()
                        revealed = true
                    },
                    modifier = Modifier.initialFocus(round.photo.id to roundNumber),
                    icon = Icons.Rounded.Visibility,
                    selected = true,
                )
                Spacer(Modifier.width(12.dp))
            }
            TvButton(
                stringResource(R.string.who_next),
                { roundNumber++ },
                modifier = Modifier.focusRequester(nextFocus),
                icon = Icons.Rounded.SkipNext,
            )
            Spacer(Modifier.padding(6.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            // La foto intera, con la stessa foto sfocata ai lati. La descrizione non dice il titolo:
            // "Foto di Giulia" svelerebbe la risposta a chi usa TalkBack.
            Box(Modifier.weight(1.4f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(SurfaceDark)) {
                BlurredBackdrop(round.photo, Modifier.fillMaxSize())
                PhotoContent(round.photo, FitMode.CONTAIN, Modifier.fillMaxSize(), contentDescription = stringResource(R.string.who_photo))
            }
            if (phoneMode && phones != null) {
                Column(Modifier.weight(.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                    // Le risposte si scelgono sui telefoni: qui si vedono soltanto, con quella giusta dopo la rivelazione.
                    round.choices.forEach { name -> ChoiceLabel(name, highlighted = revealed && WhoTookIt.isAnswer(round, name)) }
                    PhonePlayPanel(phones, revealed, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            } else Column(Modifier.weight(.6f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                round.choices.forEach { name ->
                    TvButton(
                        name,
                        onClick = {
                            if (chosen == null) {
                                chosen = name
                                played++
                                if (WhoTookIt.isAnswer(round, name)) score++
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .then(if (name == round.choices.first()) Modifier.initialFocus(round.photo.id to roundNumber) else Modifier),
                        selected = answered != null && WhoTookIt.isAnswer(round, name),
                        enabled = answered == null,
                    )
                }
            }
        }
    }
}
