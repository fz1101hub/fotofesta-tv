package it.fotofesta.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Navy = Color(0xFF050A18)
val NavySoft = Color(0xFF10182D)
val SurfaceBlue = Color(0xFF17213A)
val Coral = Color(0xFFFF795B)
val Amber = Color(0xFFFFC24B)
val Cyan = Color(0xFF55D6D2)
val TextPrimary = Color(0xFFF8FAFF)
val TextSecondary = Color(0xFFB9C3D9)

private val FotoColors = darkColorScheme(
    primary = Coral,
    onPrimary = Color(0xFF2A0803),
    secondary = Cyan,
    tertiary = Amber,
    background = Navy,
    onBackground = TextPrimary,
    surface = SurfaceBlue,
    onSurface = TextPrimary,
    surfaceVariant = NavySoft,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF55617C),
    error = Color(0xFFFFB4AB),
)

private val TvTypography = Typography(
    displayLarge = TextStyle(fontSize = 52.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun FotoFestaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FotoColors, typography = TvTypography, content = content)
}
