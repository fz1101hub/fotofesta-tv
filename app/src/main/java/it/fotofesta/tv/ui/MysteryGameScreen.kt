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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
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

@Composable
fun MysteryGameScreen(photos: List<PhotoItem>, onBack: () -> Unit) {
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

    Column(Modifier.fillMaxSize().padding(34.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.games_mystery_title),
                if (revealed) stringResource(R.string.mystery_solved, answer.title) else stringResource(R.string.mystery_question),
            )
            Spacer(Modifier.weight(1f))
            TvButton(stringResource(R.string.mystery_new_photo), { round++ }, icon = Icons.Rounded.Refresh)
            Spacer(Modifier.padding(6.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(
                Modifier.weight(1.25f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(NavySoft),
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
            Column(Modifier.weight(.75f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(stringResource(feedbackRes), style = MaterialTheme.typography.titleLarge, color = if (revealed) Cyan else Amber)
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
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        selected = revealed && choice.id == answer.id,
                        enabled = !revealed,
                    )
                }
            }
        }
    }
}

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

