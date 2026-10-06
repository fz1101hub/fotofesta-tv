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
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem

@Composable
fun MysteryGameScreen(photos: List<PhotoItem>, onBack: () -> Unit) {
    val available = photos.ifEmpty { return }
    var round by remember { mutableIntStateOf(0) }
    val answer = remember(round, photos) { available.random() }
    val choices = remember(answer, photos) {
        (listOf(answer) + available.filter { it.id != answer.id }.shuffled().take(2)).shuffled()
    }
    var revealed by remember(round) { mutableStateOf(false) }
    var hintLevel by remember(round) { mutableIntStateOf(0) }
    var feedback by remember(round) { mutableStateOf("Scegli il titolo corretto") }

    Column(Modifier.fillMaxSize().padding(34.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Foto misteriosa", if (revealed) "Esatto: ${answer.title}" else "Quale ricordo è nascosto?")
            Spacer(Modifier.weight(1f))
            TvButton("Nuova foto", { round++ }, icon = Icons.Rounded.Refresh)
            Spacer(Modifier.padding(6.dp))
            TvButton("Indietro", onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(
                Modifier.weight(1.25f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(NavySoft),
                contentAlignment = Alignment.Center,
            ) {
                PhotoContent(
                    answer,
                    FitMode.CROP,
                    Modifier.fillMaxSize().then(if (revealed) Modifier else Modifier.blur((34 - hintLevel * 10).coerceAtLeast(8).dp)),
                )
            }
            Column(Modifier.weight(.75f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(feedback, style = MaterialTheme.typography.titleLarge, color = if (revealed) Cyan else Amber)
                Spacer(Modifier.padding(8.dp))
                choices.forEach { choice ->
                    TvButton(
                        choice.title,
                        onClick = {
                            if (choice.id == answer.id) {
                                revealed = true
                                feedback = "Risposta corretta!"
                            } else {
                                hintLevel = (hintLevel + 1).coerceAtMost(2)
                                feedback = "Non è questa. La foto ora è più nitida."
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
