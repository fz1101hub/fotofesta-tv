package it.fotofesta.tv.ui

import androidx.annotation.PluralsRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.fotofesta.tv.R
import java.util.Locale

/**
 * Superficie selezionabile con il telecomando. A riposo ha il colore [color]; con il fuoco diventa
 * bianca con il testo scuro, si ingrandisce di [focusScale] e proietta un'ombra, così si vede da
 * lontano dove si trova il fuoco. [content] riceve se ha il fuoco, per adattare i colori secondari.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    color: Color = SurfaceRaised,
    contentColor: Color = TextPrimary,
    border: BorderStroke? = null,
    focusScale: Float = 1.06f,
    enabled: Boolean = true,
    role: Role = Role.Button,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "focusScale")
    var base = modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .shadow(if (focused) 14.dp else 0.dp, shape, clip = false)
        .clip(shape)
        .background(if (focused) FocusWhite else color, shape)
    if (border != null && !focused) base = base.border(border, shape)
    Box(
        base
            .alpha(if (enabled) 1f else .45f)
            // Nessun effetto "ripple": su TV il fuoco bianco è già il segnale.
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = role, onClick = onClick),
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (focused) OnFocus else contentColor) {
            content(focused)
        }
    }
}

/**
 * Pulsante a pillola. [selected] lo colora con l'accento (opzione scelta, azione principale già
 * attiva); [ghost] lo rende trasparente con un bordo, per le azioni secondarie o distruttive.
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    ghost: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = when {
            selected -> Accent
            ghost -> Color.Transparent
            else -> SurfaceRaised.copy(alpha = .92f)
        },
        contentColor = if (selected) OnAccent else TextPrimary,
        border = if (ghost) BorderStroke(1.dp, TextPrimary.copy(alpha = .28f)) else null,
        enabled = enabled,
    ) {
        Row(
            Modifier.padding(horizontal = if (ghost) 18.dp else 22.dp, vertical = if (ghost) 9.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text,
                style = if (ghost) MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp) else MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Pulsante rotondo con la sola icona, per comandi ovvi come precedente e successiva. [label] è letto da TalkBack. */
@Composable
fun TvIconButton(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    FocusSurface(onClick = onClick, modifier = modifier, shape = CircleShape, color = SurfaceRaised.copy(alpha = .92f), focusScale = 1.1f) {
        Box(Modifier.padding(11.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        }
    }
}

/** Scheda di una sorgente di foto nella home; [badge] segna quella in uso. */
@Composable
fun SourceTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(112.dp),
        shape = RoundedCornerShape(14.dp),
        color = SurfaceDark.copy(alpha = .94f),
        border = BorderStroke(1.dp, TextPrimary.copy(alpha = .08f)),
    ) { focused ->
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focused) OnFocusSecondary else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (badge != null) Badge(badge, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Scheda stretta con icona e nome, per Giochi e Impostazioni accanto alle sorgenti. */
@Composable
fun SmallTile(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(112.dp),
        shape = RoundedCornerShape(14.dp),
        color = SurfaceDark.copy(alpha = .94f),
        border = BorderStroke(1.dp, TextPrimary.copy(alpha = .08f)),
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Etichetta piccola con l'accento, come "In uso". */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.background(Accent, CircleShape).padding(horizontal = 9.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = OnAccent,
        maxLines = 1,
    )
}

/** Voce di un elenco di impostazioni: titolo, descrizione e a destra il valore o un interruttore. */
@Composable
fun SettingsItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
) {
    val state = checked?.let { stringResource(if (it) R.string.settings_on else R.string.settings_off) }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { if (state != null) stateDescription = state },
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDark,
        focusScale = 1.025f,
        enabled = enabled,
        role = if (checked != null) Role.Switch else Role.Button,
    ) { focused ->
        Row(
            Modifier.heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (focused) OnFocusSecondary else TextSecondary)
                }
            }
            when {
                checked != null -> TvSwitch(checked)
                value != null -> Text(
                    "$value ›",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (focused) OnFocusSecondary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Interruttore acceso/spento, solo visivo: lo comanda la voce che lo contiene. */
@Composable
fun TvSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(width = 40.dp, height = 22.dp).background(if (checked) Accent else SwitchOff, CircleShape)) {
        Box(
            Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(3.dp)
                .size(16.dp)
                .background(if (checked) TextPrimary else SwitchThumbOff, CircleShape)
        )
    }
}

/**
 * Sezione nella colonna di sinistra delle impostazioni. Quella aperta ha lo sfondo più chiaro e una
 * barra con l'accento; spostando il fuoco su una sezione la si apre ([onFocused]).
 */
@Composable
fun CategoryItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() },
        shape = RoundedCornerShape(10.dp),
        color = if (selected) SurfaceRaised else Color.Transparent,
        contentColor = if (selected) TextPrimary else TextSecondary,
        focusScale = 1.03f,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth()
                .drawBehind { if (selected && !focused) drawRect(Accent, size = Size(3.dp.toPx(), size.height)) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Logo e nome dell'app, in alto a sinistra. */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(Accent, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Tv, contentDescription = null, tint = OnAccent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), color = TextPrimary)
    }
}

/** Indicatore della festa in corso, con il pallino dell'accento. */
@Composable
fun PartyChip(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Background.copy(alpha = .72f), CircleShape)
            .border(1.dp, TextPrimary.copy(alpha = .18f), CircleShape)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(Accent, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary, maxLines = 1)
    }
}

/** Ora attuale, aggiornata a ogni minuto. */
@Composable
fun ClockText(modifier: Modifier = Modifier) {
    Text(
        rememberCurrentTime(),
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        color = TextPrimary,
    )
}

/** Etichetta maiuscola sopra un titolo. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.getDefault()),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Intestazione delle schermate: etichetta, titolo ed eventuale descrizione. */
@Composable
fun SectionTitle(kicker: String, title: String, subtitle: String? = null, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Kicker(kicker)
        Text(
            title,
            style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
        )
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}

/** Testo descrittivo su sfondo scuro, ad esempio per un elenco vuoto. */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier, color: Color = TextSecondary) {
    Text(text, modifier = modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, color = color)
}

/** Testo plurale localizzato con [count] come unico argomento di formato. */
@Composable
fun quantityStringResource(@PluralsRes id: Int, count: Int): String =
    LocalContext.current.resources.getQuantityString(id, count, count)

/**
 * Dà il focus a questo elemento quando compare, o di nuovo quando cambia [key]. Sulla TV, senza
 * un elemento a fuoco, la prima pressione del telecomando andrebbe persa.
 */
@Composable
fun Modifier.initialFocus(key: Any? = Unit): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(key) { runCatching { requester.requestFocus() } }
    return this.focusRequester(requester)
}
