package it.fotofesta.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.MemoryCard
import it.fotofesta.tv.model.PhotoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MemoryGameScreen(photos: List<PhotoItem>, onBack: () -> Unit) {
    val seed = photos.ifEmpty { return }
    var round by remember { mutableIntStateOf(0) }
    var cards by remember(round, photos) {
        val chosen = seed.shuffled().take(4.coerceAtMost(seed.size))
        mutableStateOf((chosen + chosen).mapIndexed { index, photo -> MemoryCard(index, photo) }.shuffled())
    }
    var locked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val complete = cards.isNotEmpty() && cards.all { it.matched }

    Column(Modifier.fillMaxSize().padding(34.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Memory", if (complete) "Hai trovato tutti i ricordi!" else "Trova le coppie")
            Spacer(Modifier.weight(1f))
            TvButton("Nuova partita", { round++ }, icon = Icons.Rounded.Refresh)
            Spacer(Modifier.padding(6.dp))
            TvButton("Indietro", onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(cards) { index, card ->
                var focused by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        if (locked || card.revealed || card.matched) return@Button
                        val now = cards.toMutableList().also { it[index] = card.copy(revealed = true) }
                        cards = now
                        val open = now.withIndex().filter { it.value.revealed && !it.value.matched }
                        if (open.size == 2) {
                            locked = true
                            scope.launch {
                                val same = open[0].value.photo.id == open[1].value.photo.id
                                delay(if (same) 450 else 900)
                                cards = cards.mapIndexed { cardIndex, value ->
                                    if (cardIndex == open[0].index || cardIndex == open[1].index) {
                                        if (same) value.copy(matched = true) else value.copy(revealed = false)
                                    } else value
                                }
                                locked = false
                            }
                        }
                    },
                    modifier = Modifier.aspectRatio(1.45f).onFocusChanged { focused = it.isFocused },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (focused) Amber else SurfaceBlue),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(if (focused) 4.dp else 6.dp),
                ) {
                    if (card.revealed || card.matched) {
                        PhotoContent(card.photo, FitMode.CROP, Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)))
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
