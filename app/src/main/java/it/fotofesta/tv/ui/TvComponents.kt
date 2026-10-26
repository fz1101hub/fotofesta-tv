package it.fotofesta.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    primary: Boolean = false,
    reducedMotion: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused && !reducedMotion) 1.04f else 1f,
        label = "tvButtonFocus",
    )
    val inverted = selected || primary
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            if (focused) 3.dp else 1.dp,
            if (focused) GraphiteWhite else if (inverted) GraphiteWhite else GraphiteOutline,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (inverted) GraphiteWhite else if (focused) GraphiteElevated else GraphiteSurface,
            contentColor = if (inverted) GraphiteBackground else GraphiteWhite,
            disabledContainerColor = GraphiteSurface,
            disabledContentColor = GraphiteSecondary,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 16.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SourceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    compact: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && !reducedMotion) 1.035f else 1f, label = "sourceFocus")
    Button(
        onClick = onClick,
        modifier = modifier.scale(scale).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) GraphiteWhite else GraphiteOutline),
        colors = ButtonDefaults.buttonColors(containerColor = if (focused) GraphiteElevated else GraphiteSurface, contentColor = GraphiteWhite),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        if (compact) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = GraphiteElevated, shape = RoundedCornerShape(10.dp)) {
                    Icon(icon, contentDescription = null, tint = GraphiteWhite, modifier = Modifier.padding(9.dp).size(28.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = GraphiteSecondary)
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(color = GraphiteElevated, shape = RoundedCornerShape(12.dp)) {
                    Icon(icon, contentDescription = null, tint = GraphiteWhite, modifier = Modifier.padding(12.dp).size(32.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = GraphiteSecondary)
            }
        }
    }
}

@Composable
fun AppHeader(sourceLabel: String, castConnected: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 46.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = GraphiteWhite) {
            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = GraphiteBackground, modifier = Modifier.padding(10.dp).size(30.dp))
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text("FotoFesta TV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(sourceLabel, style = MaterialTheme.typography.bodyMedium, color = GraphiteSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(12.dp))
        if (castConnected) {
            Row(
                Modifier.border(1.dp, GraphiteOutline, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CastConnected, contentDescription = null, tint = GraphiteWhite, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(8.dp))
                Text("Chromecast collegato", color = GraphiteWhite, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun SectionTitle(kicker: String, title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(kicker.uppercase(), color = GraphiteSecondary, style = MaterialTheme.typography.labelLarge)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = GraphiteSecondary)
    }
}

@Composable
fun SettingRow(title: String, description: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(GraphiteSurface, RoundedCornerShape(14.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = GraphiteSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}
