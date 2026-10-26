package it.fotofesta.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Usb
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.AppState

@Composable
fun SourcesScreen(
    state: AppState,
    onBack: () -> Unit,
    onGoogle: () -> Unit,
    onUsb: () -> Unit,
    onDemo: () -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { initialFocus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(GraphiteBackground).padding(horizontal = 46.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Le tue fotografie", "Scegli cosa guardare", "Seleziona una sorgente per preparare i tuoi ricordi.")
            }
            TvButton("Indietro", onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack, reducedMotion = state.settings.reducedMotion)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            PhotoSourceCard(
                "Foto demo", "Quattro fotografie incluse", Icons.Rounded.PhotoLibrary,
                onDemo, Modifier.weight(1f).focusRequester(initialFocus), demo = true, reducedMotion = state.settings.reducedMotion,
            )
            PhotoSourceCard(
                "Google Foto", "Collega e scegli dal telefono", Icons.Rounded.Cloud,
                onGoogle, Modifier.weight(1f), reducedMotion = state.settings.reducedMotion,
            )
            PhotoSourceCard(
                "Unità USB", "Apri una cartella locale", Icons.Rounded.Usb,
                onUsb, Modifier.weight(1f), reducedMotion = state.settings.reducedMotion,
            )
        }
        Text("Raccolta attiva: ${state.sourceLabel} · ${state.photos.size} fotografie",
            style = MaterialTheme.typography.bodyMedium, color = GraphiteSecondary)
    }
}

@Composable
private fun PhotoSourceCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    demo: Boolean = false,
    reducedMotion: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && !reducedMotion) 1.035f else 1f, label = "photoSourceFocus")
    Button(
        onClick = onClick,
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
                if (demo) {
                    Image(
                        painter = painterResource(R.drawable.demo_coast),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(68.dp), tint = GraphiteWhite)
                }
            }
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(description, color = GraphiteSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
