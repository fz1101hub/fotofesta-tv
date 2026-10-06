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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "focus")
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(14.dp),
        border = if (focused) BorderStroke(3.dp, Amber) else BorderStroke(1.dp, if (selected) Coral else Color.Transparent),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                selected -> Coral
                focused -> Color(0xFF2B3859)
                else -> SurfaceBlue
            },
            contentColor = TextPrimary,
            disabledContainerColor = Color(0xFF232A3A),
            disabledContentColor = TextSecondary,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 15.dp),
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
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, label = "cardFocus")
    Button(
        onClick = onClick,
        modifier = modifier.scale(scale).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Amber else Color(0xFF394563)),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = TextPrimary),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(accent.copy(alpha = .34f), SurfaceBlue, NavySoft))
            ).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = accent.copy(alpha = .18f), shape = RoundedCornerShape(14.dp)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(12.dp).size(34.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
}

@Composable
fun AppHeader(sourceLabel: String, castConnected: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 42.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = Coral.copy(alpha = .16f)) {
            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = Coral, modifier = Modifier.padding(10.dp).size(30.dp))
        }
        Spacer(Modifier.size(14.dp))
        Column {
            Text("FotoFesta TV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(sourceLabel, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        Spacer(Modifier.weight(1f))
        if (castConnected) {
            Row(
                Modifier.border(1.dp, Cyan, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CastConnected, contentDescription = null, tint = Cyan, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(8.dp))
                Text("Chromecast collegato", color = Cyan, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun SectionTitle(kicker: String, title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(kicker.uppercase(), color = Coral, style = MaterialTheme.typography.labelLarge)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}

@Composable
fun SettingRow(title: String, description: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(NavySoft, RoundedCornerShape(18.dp)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}
