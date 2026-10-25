package it.fotofesta.tv.ui

import android.os.Build
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
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import it.fotofesta.tv.R
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem
import kotlinx.coroutines.delay

@Composable
fun MysteryGameScreen(photos: List<PhotoItem>, phones: PhonePlay?, onBack: () -> Unit) {
    if (photos.size < MIN_GAME_PHOTOS) {
        NotEnoughPhotos(stringResource(R.string.games_mystery_title), onBack)
        return
    }
    var round by remember { mutableIntStateOf(0) }
    // Non è uno State osservato: serve solo a non riproporre subito la stessa foto.
    val lastAnswerId = remember { arrayOfNulls<String>(1) }
    val answer = remember(round, photos) {
        photos.filter { it.id != lastAnswerId[0] }.ifEmpty { photos }.random().also { lastAnswerId[0] = it.id }
    }
    val choices = remember(answer, photos) {
        (listOf(answer) + photos.filter { it.id != answer.id }.shuffled().take(2)).shuffled()
    }
    var revealed by remember(round, photos) { mutableStateOf(false) }
    var hintLevel by remember(round, photos) { mutableIntStateOf(0) }
    var feedbackRes by remember(round, photos) { mutableIntStateOf(R.string.mystery_prompt) }
    val newPhotoFocus = remember { FocusRequester() }
    // Dopo la risposta giusta le scelte si disattivano: il focus passa a "Nuova foto".
    LaunchedEffect(revealed) { if (revealed) runCatching { newPhotoFocus.requestFocus() } }
    // Con i telefoni rispondono gli invitati: la domanda va alla pagina della festa e la foto si fa
    // più nitida da sola ogni tanto; il telecomando mostra la risposta.
    var phoneMode by remember { mutableStateOf(false) }
    val question = stringResource(R.string.mystery_question)
    LaunchedEffect(answer, phoneMode) {
        if (!phoneMode || phones == null) return@LaunchedEffect
        phones.game.ask(question, choices.map { it.title }, choices.indexOf(answer))
        repeat(2) {
            delay(PHONE_HINT_DELAY_MS)
            if (!revealed) hintLevel = (hintLevel + 1).coerceAtMost(2)
        }
    }
    StopPhoneGameOnExit(phones, phoneMode)

    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.games_mystery_title),
                if (revealed) stringResource(R.string.mystery_solved, answer.title) else stringResource(R.string.mystery_question),
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
                    modifier = Modifier.initialFocus(answer.id to phoneMode),
                    icon = Icons.Rounded.Visibility,
                    selected = true,
                )
                Spacer(Modifier.width(12.dp))
            }
            TvButton(
                stringResource(R.string.mystery_new_photo),
                { round++ },
                modifier = Modifier.focusRequester(newPhotoFocus),
                icon = Icons.Rounded.Refresh,
            )
            Spacer(Modifier.padding(6.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(
                Modifier.weight(1.25f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(SurfaceDark),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    revealed -> PhotoContent(answer, FitMode.CROP, Modifier.fillMaxSize())
                    // Modifier.blur funziona solo da Android 12: prima si usa una versione a bassissima risoluzione.
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PhotoContent(
                        answer,
                        FitMode.CROP,
                        Modifier.fillMaxSize().blur((34 - hintLevel * 10).dp),
                        contentDescription = stringResource(R.string.mystery_hidden_photo),
                    )
                    else -> LowResolutionPhoto(answer, sizePx = 10 + hintLevel * 12, Modifier.fillMaxSize())
                }
            }
            if (phoneMode && phones != null) {
                Column(Modifier.weight(.75f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                    choices.forEach { choice -> ChoiceLabel(choice.title, highlighted = revealed && choice.id == answer.id) }
                    PhonePlayPanel(phones, revealed, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            } else Column(Modifier.weight(.75f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(stringResource(feedbackRes), style = MaterialTheme.typography.titleLarge, color = if (revealed) Accent else TextPrimary)
                Spacer(Modifier.padding(8.dp))
                choices.forEach { choice ->
                    TvButton(
                        choice.title,
                        onClick = {
                            if (choice.id == answer.id) {
                                revealed = true
                                feedbackRes = R.string.mystery_correct
                            } else {
                                hintLevel = (hintLevel + 1).coerceAtMost(2)
                                feedbackRes = R.string.mystery_wrong
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .then(if (choice == choices.first()) Modifier.initialFocus(answer.id) else Modifier),
                        selected = revealed && choice.id == answer.id,
                        enabled = !revealed,
                    )
                }
            }
        }
    }
}

/** Con i telefoni la foto misteriosa si fa più nitida da sola a questi intervalli. */
private const val PHONE_HINT_DELAY_MS = 8_000L

@Composable
private fun LowResolutionPhoto(photo: PhotoItem, sizePx: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val description = stringResource(R.string.mystery_hidden_photo)
    val request = remember(photo.id, sizePx) {
        ImageRequest.Builder(context)
            .data(photo.ref.coilModel())
            .size(sizePx, sizePx)
            .precision(Precision.EXACT)
            // Chiave dedicata: altrimenti Coil potrebbe riusare la versione nitida già in memoria.
            .memoryCacheKey("mystery:${photo.id}:$sizePx")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = description,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.Low,
        modifier = modifier,
    )
}

