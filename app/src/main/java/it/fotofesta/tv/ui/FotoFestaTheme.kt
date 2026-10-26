package it.fotofesta.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Graphite Edition: deliberately monochromatic. Photographs supply the colour.
val GraphiteBackground = Color(0xFF0B0B0D)
val GraphiteSurface = Color(0xFF19191D)
val GraphiteElevated = Color(0xFF29292F)
val GraphiteOutline = Color(0xFF53535B)
val GraphiteWhite = Color(0xFFFAFAFA)
val GraphiteSecondary = Color(0xFFA6A6AD)

// Retain compatibility with screens that still use the original token names.
val Navy = GraphiteBackground
val NavySoft = GraphiteSurface
val SurfaceBlue = GraphiteElevated
val Coral = GraphiteWhite
val Amber = GraphiteWhite
val Cyan = GraphiteWhite
val TextPrimary = GraphiteWhite
val TextSecondary = GraphiteSecondary

private val FotoColors = darkColorScheme(
    primary = GraphiteWhite,
    onPrimary = GraphiteBackground,
    secondary = GraphiteSecondary,
    onSecondary = GraphiteBackground,
    tertiary = GraphiteWhite,
    onTertiary = GraphiteBackground,
    background = GraphiteBackground,
    onBackground = GraphiteWhite,
    surface = GraphiteSurface,
    onSurface = GraphiteWhite,
    surfaceVariant = GraphiteElevated,
    onSurfaceVariant = GraphiteSecondary,
    outline = GraphiteOutline,
    error = Color(0xFFE0B7B7),
)

private val TvTypography = Typography(
    displayLarge = TextStyle(fontSize = 52.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun FotoFestaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FotoColors, typography = TvTypography, content = content)
}
