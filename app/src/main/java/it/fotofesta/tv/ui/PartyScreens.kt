package it.fotofesta.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.fotofesta.tv.R
import it.fotofesta.tv.guests.PartyHub
import it.fotofesta.tv.model.PartyUiState
import it.fotofesta.tv.model.PrizeDraw
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------
// Sopra lo slideshow: reazioni che salgono e dediche dei telefoni.
// ---------------------------------------------------------------------------------------------

private class FloatingReaction(val serial: Long, val emoji: String, val lane: Float)

/** Reazioni dei telefoni: ogni emoji sale dal basso a destra e svanisce in tre secondi. */
@Composable
fun BoxScope.ReactionsOverlay(reactions: Flow<PartyHub.ReactionEvent>) {
    val floating = remember { mutableStateListOf<FloatingReaction>() }
    LaunchedEffect(reactions) {
        reactions.collect { event ->
            if (floating.size >= MAX_FLOATING_REACTIONS) floating.removeAt(0)
            floating += FloatingReaction(event.serial, event.reaction.emoji, Random.nextFloat())
        }
    }
    val reduced = LocalReducedMotion.current
    floating.toList().forEach { item ->
        key(item.serial) { FloatingEmoji(item, reduced) { floating.remove(item) } }
    }
}

@Composable
private fun BoxScope.FloatingEmoji(item: FloatingReaction, reduced: Boolean, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(if (reduced) 1_600 else 3_200, easing = LinearEasing))
        onDone()
    }
    val value = progress.value
    Text(
        item.emoji,
        modifier = Modifier.align(Alignment.BottomEnd)
            // Corsie nella parte destra dello schermo, lontano dalla didascalia e dal QR.
            .padding(end = (56 + item.lane * 300).dp, bottom = 150.dp)
            .offset(y = if (reduced) 0.dp else (-420 * value).dp)
            .graphicsLayer {
                alpha = 1f - value * value
                val scale = if (reduced) 1f else .7f + .5f * minOf(1f, value * 4)
                scaleX = scale
                scaleY = scale
            },
        fontSize = 54.sp,
    )
}

/**
 * Dediche in sovrimpressione: le nuove una alla volta, per nove secondi ciascuna; quando non ne
 * arrivano, ogni tanto ne torna una di quelle già lette, come un libro degli ospiti che si sfoglia.
 */
@Composable
fun BoxScope.MessageBanner(messages: List<PartyHub.Message>) {
    val seen = remember { mutableSetOf<String>() }
    val queue = remember { mutableStateListOf<PartyHub.Message>() }
    var current by remember { mutableStateOf<PartyHub.Message?>(null) }
    val latest by rememberUpdatedState(messages)
    LaunchedEffect(messages) {
        // All'apertura dello slideshow si parte dall'ultima dedica arrivata, non da tutte.
        if (seen.isEmpty() && messages.isNotEmpty()) {
            messages.dropLast(1).forEach { seen += it.id }
        }
        messages.filter { seen.add(it.id) }.forEach { queue += it }
    }
    LaunchedEffect(Unit) {
        var idle = 0L
        while (true) {
            val next = queue.firstOrNull()?.also { queue.removeAt(0) }
                ?: latest.takeIf { idle >= MESSAGE_REPLAY_MS && it.isNotEmpty() }?.random()
            if (next != null) {
                idle = 0
                current = next
                delay(MESSAGE_SHOW_MS)
                current = null
                delay(600)
            } else {
                delay(500)
                idle += 500
            }
        }
    }
    AnimatedVisibility(
        visible = current != null,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp),
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(400)),
    ) {
        val message = current ?: return@AnimatedVisibility
        Column(
            Modifier.widthIn(max = 900.dp)
                .background(SurfaceRaised.copy(alpha = .94f), RoundedCornerShape(18.dp))
                .padding(horizontal = 26.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Kicker(message.name?.let { stringResource(R.string.guestbook_from, it) } ?: stringResource(R.string.guestbook_kicker))
            Text(message.text, style = MaterialTheme.typography.headlineSmall, color = TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val MAX_FLOATING_REACTIONS = 24
private const val MESSAGE_SHOW_MS = 9_000L
private const val MESSAGE_REPLAY_MS = 45_000L

// ---------------------------------------------------------------------------------------------
// Sondaggio lampo
// ---------------------------------------------------------------------------------------------

private class PollQuestion(val question: String, val choices: List<String>)

/**
 * Sondaggio con i telefoni: si sceglie una domanda, gli invitati votano dalla pagina della festa e
 * alla fine la TV mostra i voti. Alcune domande hanno come risposte i nomi di chi partecipa.
 */
@Composable
fun PollScreen(play: PhonePlay?, candidates: () -> List<String>, onBack: () -> Unit) {
    var poll by remember { mutableStateOf<PollQuestion?>(null) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    StopPhoneGameOnExit(play, active = poll != null)
    val presets = pollPresets()
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(
                stringResource(R.string.poll_kicker),
                poll?.question ?: stringResource(R.string.games_poll_title),
                if (poll == null) stringResource(if (play != null) R.string.poll_choose else R.string.poll_need_party) else null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, modifier = if (play == null) Modifier.initialFocus() else Modifier, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        val current = poll
        if (play == null) return@Column
        if (current == null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(presets.size) { index ->
                    val preset = presets[index]
                    TvButton(
                        preset.question,
                        onClick = {
                            val choices = preset.choices ?: candidates().shuffled().take(MAX_NAME_CHOICES)
                            if (choices.size >= 2) {
                                poll = PollQuestion(preset.question, choices)
                                revealed = false
                                play.game.askPoll(preset.question, choices)
                            }
                        },
                        modifier = if (index == 0) Modifier.initialFocus() else Modifier,
                        icon = Icons.Rounded.Poll,
                        // Le domande con i nomi servono almeno due partecipanti con un nome.
                        enabled = preset.choices != null || candidates().size >= 2,
                    )
                }
            }
        } else {
            val tally by produceState(emptyList<Int>(), play.game, current) {
                while (true) {
                    value = play.game.tally()
                    delay(500)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val total = tally.sum().coerceAtLeast(1)
                    val best = tally.maxOrNull() ?: 0
                    current.choices.forEachIndexed { index, choice ->
                        val votes = tally.getOrElse(index) { 0 }
                        PollBar(choice, votes, if (revealed) votes.toFloat() / total else 0f, highlighted = revealed && votes == best && votes > 0)
                    }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(
                            stringResource(R.string.poll_reveal),
                            { revealed = true; play.game.reveal() },
                            modifier = Modifier.initialFocus(current),
                            icon = Icons.Rounded.Check,
                            enabled = !revealed,
                        )
                        TvButton(stringResource(R.string.poll_new), { play.game.stop(); poll = null }, icon = Icons.Rounded.Refresh)
                    }
                }
                PhonePlayPanel(play, revealed = false, modifier = Modifier.width(340.dp))
            }
        }
    }
}

@Composable
private fun PollBar(label: String, votes: Int, fraction: Float, highlighted: Boolean) {
    Box(
        Modifier.fillMaxWidth().height(58.dp).background(SurfaceDark, RoundedCornerShape(14.dp)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(if (highlighted) Accent else SurfaceRaised, RoundedCornerShape(14.dp)))
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = if (highlighted) OnAccent else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fraction > 0f || highlighted) {
                Text(quantityStringResource(R.plurals.poll_votes, votes), style = MaterialTheme.typography.titleMedium, color = if (highlighted) OnAccent else TextSecondary)
            }
        }
    }
}

/** Domanda pronta; senza [choices] le risposte sono i nomi di chi partecipa. */
private class PollPreset(val question: String, val choices: List<String>?)

@Composable
private fun pollPresets(): List<PollPreset> = listOf(
    PollPreset(stringResource(R.string.poll_q_king), null),
    PollPreset(stringResource(R.string.poll_q_last_dance), null),
    PollPreset(stringResource(R.string.poll_q_style), null),
    PollPreset(stringResource(R.string.poll_q_moment), stringResource(R.string.poll_c_moment).split('|')),
    PollPreset(stringResource(R.string.poll_q_vote), stringResource(R.string.poll_c_vote).split('|')),
    PollPreset(stringResource(R.string.poll_q_sleep), stringResource(R.string.poll_c_sleep).split('|')),
)

/** Risposte con i nomi: quattro, come nei giochi con i telefoni. */
private const val MAX_NAME_CHOICES = 4

// ---------------------------------------------------------------------------------------------
// Estrazione a premi
// ---------------------------------------------------------------------------------------------

/**
 * Estrazione tra chi ha partecipato con un nome: i nomi scorrono veloci per qualche secondo e si
 * fermano sul vincitore. Chi ha già vinto non viene estratto di nuovo finché non si ricomincia.
 */
@Composable
fun LotteryScreen(candidates: () -> List<String>, onBack: () -> Unit) {
    val winners = rememberSaveable(saver = listSaver<SnapshotStateList<String>, String>({ it.toList() }, { it.toMutableStateList() })) {
        mutableStateListOf()
    }
    var shown by remember { mutableStateOf<String?>(null) }
    var spinning by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val count = remember(revision) { candidates().size }
    val reduced = LocalReducedMotion.current

    fun draw() {
        if (spinning) return
        val names = candidates()
        val winner = PrizeDraw.draw(names, winners)
        if (winner == null) {
            exhausted = names.isNotEmpty()
            shown = null
            return
        }
        exhausted = false
        spinning = true
        scope.launch {
            if (!reduced) {
                val start = System.nanoTime()
                while (System.nanoTime() - start < SPIN_NANOS) {
                    shown = names.random()
                    delay(85)
                }
            }
            shown = winner
            winners += winner
            spinning = false
            revision++
        }
    }

    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(
                stringResource(R.string.lottery_kicker),
                stringResource(R.string.games_lottery_title),
                if (count == 0) stringResource(R.string.lottery_none) else quantityStringResource(R.plurals.lottery_candidates, count),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(if (spinning) Icons.Rounded.Casino else Icons.Rounded.Celebration, contentDescription = null, tint = Accent, modifier = Modifier.size(56.dp))
                Text(
                    when {
                        shown != null -> shown!!
                        exhausted -> stringResource(R.string.lottery_all_drawn)
                        else -> stringResource(R.string.lottery_ready)
                    },
                    style = MaterialTheme.typography.displayMedium,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (winners.isNotEmpty() && !spinning) {
                    Text(
                        stringResource(R.string.lottery_winners, winners.joinToString(" · ")),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                stringResource(if (winners.isEmpty()) R.string.lottery_draw else R.string.lottery_again),
                ::draw,
                modifier = Modifier.initialFocus(),
                icon = Icons.Rounded.Casino,
                enabled = !spinning && count > 0,
            )
            if (winners.isNotEmpty()) {
                TvButton(stringResource(R.string.lottery_reset), { winners.clear(); shown = null; exhausted = false; revision++ }, icon = Icons.Rounded.Refresh, enabled = !spinning)
            }
        }
    }
}

private const val SPIN_NANOS = 2_400_000_000L

// ---------------------------------------------------------------------------------------------
// Libro degli ospiti
// ---------------------------------------------------------------------------------------------

/** Tutte le dediche della festa, dalla più recente; in cima quelle da approvare. */
@Composable
fun GuestbookScreen(
    party: PartyUiState,
    onApprove: (PartyHub.Message) -> Unit,
    onDiscard: (PartyHub.Message) -> Unit,
    onApproveAll: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(
                stringResource(R.string.guestbook_kicker),
                stringResource(R.string.guestbook_title),
                if (party.messages.isEmpty() && party.pendingMessages.isEmpty()) stringResource(R.string.guestbook_empty)
                else quantityStringResource(R.plurals.guestbook_count, party.messages.size),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            if (party.pendingMessages.size > 1) {
                TvButton(stringResource(R.string.guestbook_approve_all), onApproveAll, icon = Icons.Rounded.Check)
                Spacer(Modifier.width(12.dp))
            }
            TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.initialFocus(), icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(party.pendingMessages, key = { "p" + it.id }) { message ->
                MessageCard(message, pending = true) {
                    TvButton(stringResource(R.string.guestbook_show), { onApprove(message) }, icon = Icons.Rounded.Check)
                    TvButton(stringResource(R.string.guestbook_discard), { onDiscard(message) }, icon = Icons.Rounded.Close, ghost = true)
                }
            }
            items(party.messages.asReversed(), key = { it.id }) { message ->
                MessageCard(message, pending = false) {
                    TvButton(stringResource(R.string.guestbook_discard), { onDiscard(message) }, icon = Icons.Rounded.Close, ghost = true)
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: PartyHub.Message, pending: Boolean, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(16.dp))
            .then(if (pending) Modifier.background(Accent.copy(alpha = .08f), RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Kicker(
                buildString {
                    append(message.name ?: stringResource(R.string.guestbook_anonymous))
                    if (pending) append(" · ").append(stringResource(R.string.guestbook_pending))
                },
            )
            Text(message.text, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
    }
}

// ---------------------------------------------------------------------------------------------
// Regia: annunci e regista di turno
// ---------------------------------------------------------------------------------------------

/** Annuncio della regia, in grande al centro per quindici secondi dopo l'arrivo. */
@Composable
fun BoxScope.AnnouncementOverlay(announcement: PartyHub.Announcement?) {
    var visible by remember { mutableStateOf<PartyHub.Announcement?>(null) }
    LaunchedEffect(announcement?.serial) {
        val current = announcement ?: return@LaunchedEffect
        // Un annuncio già vecchio (ad esempio aprendo lo slideshow dopo) non ricompare.
        val left = ANNOUNCEMENT_SCREEN_MS - (System.currentTimeMillis() - current.atMillis)
        if (left <= 0) return@LaunchedEffect
        visible = current
        delay(left)
        if (visible?.serial == current.serial) visible = null
    }
    AnimatedVisibility(
        visible = visible != null,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(500)),
    ) {
        val shown = visible ?: return@AnimatedVisibility
        Column(
            Modifier.widthIn(max = 1100.dp)
                .background(Accent, RoundedCornerShape(28.dp))
                .padding(horizontal = 48.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.announce_kicker).uppercase(), style = MaterialTheme.typography.labelLarge, color = OnAccent)
            Text(shown.text, style = MaterialTheme.typography.displaySmall, color = OnAccent, textAlign = TextAlign.Center)
        }
    }
}

/** Chi dirige lo slideshow dal telefono, con il tempo che resta. */
@Composable
fun BoxScope.DirectorBadge(director: PartyHub.Director?) {
    val current = director ?: return
    val remaining by produceState(((current.untilMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0), current) {
        while (true) {
            value = ((current.untilMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (value == 0L) break
            delay(500)
        }
    }
    if (remaining <= 0) return
    Row(
        Modifier.align(Alignment.TopCenter)
            .padding(top = 27.dp)
            .background(SurfaceRaised.copy(alpha = .94f), RoundedCornerShape(50))
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Videocam, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Text(
            stringResource(R.string.director_now, current.name, "%d:%02d".format(remaining / 60, remaining % 60)),
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
        )
    }
}

private const val ANNOUNCEMENT_SCREEN_MS = 15_000L

/** Annuncio dalla TV: frasi pronte da scegliere con il telecomando, oppure un testo libero. */
@Composable
fun AnnounceScreen(onAnnounce: (String) -> Unit, onBack: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val presets = listOf(
        stringResource(R.string.announce_p_buffet),
        stringResource(R.string.announce_p_cake),
        stringResource(R.string.announce_p_group),
        stringResource(R.string.announce_p_dance),
        stringResource(R.string.announce_p_last),
    )
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.announce_kicker), stringResource(R.string.guests_announce), stringResource(R.string.announce_body), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            presets.take(3).forEachIndexed { index, preset ->
                TvButton(preset, { onAnnounce(preset) }, modifier = if (index == 0) Modifier.initialFocus() else Modifier, icon = Icons.Rounded.Campaign)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            presets.drop(3).forEach { preset -> TvButton(preset, { onAnnounce(preset) }, icon = Icons.Rounded.Campaign) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(
                text,
                { text = it.take(PartyHub.MAX_ANNOUNCEMENT_LENGTH) },
                stringResource(R.string.announce_custom),
                modifier = Modifier.weight(1f),
            )
            TvButton(stringResource(R.string.announce_send), { onAnnounce(text) }, icon = Icons.Rounded.Campaign, enabled = text.isNotBlank())
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Regole della festa: tre minuti senza telefoni, rullino da sviluppare, foto che invecchiano
// ---------------------------------------------------------------------------------------------

/** Tre minuti senza telefoni: la foto resta sullo schermo, abbassata, con il tempo che manca. */
@Composable
fun BoxScope.QuietOverlay(untilMillis: Long) {
    if (untilMillis <= 0) return
    val left by produceState(((untilMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0), untilMillis) {
        while (value > 0) {
            delay(500)
            value = ((untilMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        }
    }
    if (left <= 0) return
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .45f)))
    Column(
        Modifier.align(Alignment.Center)
            .background(SurfaceRaised.copy(alpha = .94f), RoundedCornerShape(28.dp))
            .padding(horizontal = 48.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("📵", fontSize = 64.sp)
        Text(stringResource(R.string.games_quiet_title), style = MaterialTheme.typography.headlineMedium, color = TextPrimary, textAlign = TextAlign.Center)
        Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.displaySmall, color = Accent)
    }
}

/** Rullino usa e getta non ancora sviluppato: quanti scatti sono già "in macchina". */
@Composable
fun BoxScope.DisposableBadge(shots: Int) {
    Row(
        Modifier.align(Alignment.BottomEnd)
            .padding(end = 48.dp, bottom = 120.dp)
            .background(SurfaceRaised.copy(alpha = .94f), RoundedCornerShape(50))
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Text(quantityStringResource(R.plurals.disposable_shots, shots), style = MaterialTheme.typography.titleSmall, color = TextPrimary)
    }
}

/** Foto che invecchiano: tinta seppia e un po' di trasparenza crescenti con [age] (0–1). */
fun Modifier.aging(age: Float): Modifier {
    if (age <= 0f) return this
    val sepia = androidx.compose.ui.graphics.Color(0xFF8A6A3A)
    // La fusione "Color" (Android 10+) tinge senza coprire; prima si usa una velatura leggera.
    val advanced = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
    return graphicsLayer { alpha = 1f - age * .2f }.drawWithContent {
        drawContent()
        drawRect(
            sepia,
            alpha = if (advanced) age * .55f else age * .25f,
            blendMode = if (advanced) androidx.compose.ui.graphics.BlendMode.Color else androidx.compose.ui.graphics.BlendMode.SrcOver,
        )
    }
}
