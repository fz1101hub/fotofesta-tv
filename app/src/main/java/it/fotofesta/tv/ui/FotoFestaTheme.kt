package it.fotofesta.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import it.fotofesta.tv.R

// Palette ad alto contrasto: sfondo quasi nero, testi bianchi e grigio chiaro (almeno 6:1), un solo
// colore d'accento (il corallo del logo) e il fuoco del telecomando in bianco pieno.
val Background = Color(0xFF0D0F13)
val SurfaceDark = Color(0xFF1B1E25)
val SurfaceRaised = Color(0xFF262A33)
val Outline = Color(0xFF343945)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFC8CDD6)
val TextTertiary = Color(0xFF9AA2AF)
val Accent = Color(0xFFFF7A57)
val OnAccent = Color(0xFF1A0B06)
val FocusWhite = Color(0xFFFFFFFF)
val OnFocus = Color(0xFF0D0F13)
val OnFocusSecondary = Color(0xFF3A404C)
val SwitchOff = Color(0xFF4A505D)
val SwitchThumbOff = Color(0xFFD5D9E0)
val ErrorText = Color(0xFFFF9C85)

/** Figtree (licenza SIL OFL, testo in assets/licenses): titoli decisi e cifre leggibili da lontano. */
val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
    Font(R.font.figtree_extrabold, FontWeight.ExtraBold),
)

private val FotoColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    secondary = TextSecondary,
    onSecondary = Background,
    tertiary = Accent,
    onTertiary = OnAccent,
    background = Background,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    error = ErrorText,
)

private fun style(size: Int, weight: FontWeight, lineHeight: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Figtree,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
)

/**
 * Scala tipografica per una TV vista dal divano (960×540 dp). I nomi seguono Material 3:
 * display per il titolo della home, headline per i titoli delle schermate, title per schede e voci,
 * body per i testi, label per pulsanti ed etichette.
 */
private val TvTypography = Typography(
    displayLarge = style(46, FontWeight.ExtraBold, 50, tracking = -0.9),
    headlineLarge = style(38, FontWeight.ExtraBold, 44, tracking = -0.7),
    headlineMedium = style(28, FontWeight.ExtraBold, 34, tracking = -0.5),
    titleLarge = style(24, FontWeight.ExtraBold, 30, tracking = -0.2),
    titleMedium = style(16, FontWeight.Bold, 21),
    titleSmall = style(15, FontWeight.Bold, 20),
    bodyLarge = style(17, FontWeight.Normal, 24),
    bodyMedium = style(15, FontWeight.Normal, 21),
    bodySmall = style(13, FontWeight.Normal, 18),
    labelLarge = style(16, FontWeight.Bold, 20),
    labelMedium = style(12, FontWeight.Bold, 16, tracking = 1.7),
    labelSmall = style(11, FontWeight.ExtraBold, 14),
)

/**
 * "Riduci movimento" delle impostazioni: oltre a transizioni e movimento lento dello slideshow,
 * spegne l'ingrandimento animato del fuoco e la dissolvenza delle foto nella home.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun FotoFestaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FotoColors, typography = TvTypography, content = content)
}
