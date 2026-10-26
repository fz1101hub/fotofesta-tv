package it.fotofesta.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem

@Composable
fun GamesScreen(
    photos: List<PhotoItem>,
    onBack: () -> Unit,
    onMemory: () -> Unit,
    onMystery: () -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { initialFocus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(GraphiteBackground).padding(horizontal = 46.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Momenti da condividere", "Gioca con i tuoi ricordi", "Tutti i giochi funzionano con il telecomando.")
            }
            TvButton("Indietro", onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            GamePhotoCard(
                title = "Memory dei ricordi",
                description = "Trova le coppie nascoste",
                icon = Icons.Rounded.GridView,
                photo = photos.getOrNull(0),
                onClick = onMemory,
                modifier = Modifier.weight(1f).focusRequester(initialFocus),
                enabled = photos.isNotEmpty(),
            )
            GamePhotoCard(
                title = "Foto misteriosa",
                description = "Indovina la fotografia",
                icon = Icons.Rounded.Visibility,
                photo = photos.getOrNull(1) ?: photos.firstOrNull(),
                onClick = onMystery,
                modifier = Modifier.weight(1f),
                enabled = photos.isNotEmpty(),
                mystery = true,
            )
        }
        Text(
            if (photos.isNotEmpty()) "Raccolta attiva: ${photos.size} fotografie" else "Seleziona prima una raccolta di fotografie.",
            style = MaterialTheme.typography.bodyMedium, color = GraphiteSecondary,
        )
    }
}

@Composable
private fun GamePhotoCard(
    title: String,
    description: String,
    icon: ImageVector,
    photo: PhotoItem?,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    mystery: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "gameCardFocus")
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxSize().scale(scale).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) GraphiteWhite else GraphiteOutline),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) GraphiteElevated else GraphiteSurface,
            contentColor = GraphiteWhite,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().weight(1f).background(GraphiteElevated),
                contentAlignment = Alignment.Center,
            ) {
                if (photo != null) {
                    PhotoContent(photo, FitMode.CROP, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (mystery) .57f else .40f)))
                }
                if (mystery) {
                    Text("?", style = MaterialTheme.typography.displayLarge, color = GraphiteWhite)
                } else {
                    Icon(icon, contentDescription = null, tint = GraphiteWhite, modifier = Modifier.size(72.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(description, color = GraphiteSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(icon, contentDescription = null, tint = GraphiteWhite)
            }
        }
    }
}
