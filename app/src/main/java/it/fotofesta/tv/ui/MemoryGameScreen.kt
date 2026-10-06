package it.fotofesta.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.MemoryCard
import it.fotofesta.tv.model.PhotoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MemoryGameScreen(photos: List<PhotoItem>, onBack: () -> Unit) {
    if (photos.size < MIN_GAME_PHOTOS) {
        NotEnoughPhotos(stringResource(R.string.memory_title), onBack)
        return
    }
    var round by remember { mutableIntStateOf(0) }
    var cards by remember(round, photos) {
        val chosen = photos.shuffled().take(4)
        mutableStateOf((chosen + chosen).mapIndexed { index, photo -> MemoryCard(index, photo) }.shuffled())
    }
    var moves by remember(round, photos) { mutableIntStateOf(0) }
    var pendingCheck by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val complete = cards.all { it.matched }

    fun newGame() {
        pendingCheck?.cancel()
        pendingCheck = null
        round++
    }

    Column(Modifier.fillMaxSize().padding(34.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.memory_kicker, moves),
                if (complete) stringResource(R.string.memory_complete, moves) else stringResource(R.string.memory_find_pairs),
            )
            Spacer(Modifier.weight(1f))
            TvButton(stringResource(R.string.memory_new_game), ::newGame, icon = Icons.Rounded.Refresh)
            Spacer(Modifier.padding(6.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(cards, key = { _, card -> card.key }) { index, card ->
                var focused by remember { mutableStateOf(false) }
                val cardDescription = when {
                    card.matched -> stringResource(R.string.memory_card_matched, card.photo.title)
                    card.revealed -> card.photo.title
                    else -> stringResource(R.string.memory_card_covered, index + 1)
                }
                Button(
                    onClick = {
                        if (pendingCheck != null || card.revealed || card.matched) return@Button
                        val now = cards.toMutableList().also { it[index] = card.copy(revealed = true) }
                        cards = now
                        val open = now.withIndex().filter { it.value.revealed && !it.value.matched }
                        if (open.size == 2) {
                            moves++
                            val same = open[0].value.photo.id == open[1].value.photo.id
                            pendingCheck = scope.launch {
                                delay(if (same) 450 else 900)
                                cards = cards.mapIndexed { cardIndex, value ->
                                    if (cardIndex == open[0].index || cardIndex == open[1].index) {
                                        if (same) value.copy(matched = true) else value.copy(revealed = false)
                                    } else value
                                }
                                pendingCheck = null
                            }
                        }
                    },
                    modifier = Modifier.aspectRatio(1.45f)
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = cardDescription },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (focused) Amber else SurfaceBlue),
                    contentPadding = PaddingValues(if (focused) 4.dp else 6.dp),
                ) {
                    if (card.revealed || card.matched) {
                        PhotoContent(card.photo, FitMode.CROP, Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)), contentDescription = null)
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Coral, Color(0xFF7D3C98))), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text("?", style = MaterialTheme.typography.displayLarge) }
                    }
                }
            }
        }
    }
}
