package it.fotofesta.tv.ui

import android.text.format.DateFormat
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PartyAwards
import it.fotofesta.tv.model.PhotoItem
import java.util.Date

/**
 * Premi di fine festa: "Foto della serata" (più reazioni dai telefoni), "Obiettivo d'oro", prima
 * foto e gran finale, con il riepilogo della festa.
 */
@Composable
fun AwardsScreen(awards: PartyAwards, onBack: () -> Unit, onCapsule: (() -> Unit)? = null) {
    val summary = buildList {
        add(quantityStringResource(R.plurals.awards_photo_count, awards.photos))
        if (awards.guests > 0) add(quantityStringResource(R.plurals.awards_guest_count, awards.guests))
    }.joinToString(" · ")
    Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.awards_kicker), stringResource(R.string.awards_title), summary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            // Fine festa: tutto sulla chiavetta, da riaprire tra un anno.
            if (onCapsule != null) {
                TvButton(stringResource(R.string.capsule_button), onCapsule, icon = Icons.Rounded.HourglassTop)
                Spacer(Modifier.width(12.dp))
            }
            TvButton(stringResource(R.string.action_back), onBack, modifier = Modifier.initialFocus(), icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            awards.favorite?.let { favorite ->
                AwardCard(
                    Icons.Rounded.Favorite,
                    stringResource(R.string.awards_favorite),
                    favorite.photo.sender?.takeIf { it.isNotBlank() }?.trim() ?: favorite.photo.title,
                    quantityStringResource(R.plurals.awards_reaction_count, favorite.reactions),
                    favorite.photo,
                    Modifier.weight(1f),
                )
            }
            awards.topPhotographer?.let { top ->
                AwardCard(
                    Icons.Rounded.EmojiEvents,
                    stringResource(R.string.awards_golden_lens),
                    top.name,
                    quantityStringResource(R.plurals.awards_photo_count, top.count),
                    top.photo,
                    Modifier.weight(1f),
                )
            }
            awards.firstPhoto?.let { photo ->
                AwardCard(Icons.Rounded.Flag, stringResource(R.string.awards_first), senderName(photo), arrivalTime(photo), photo, Modifier.weight(1f))
            }
            awards.lastPhoto?.let { photo ->
                AwardCard(Icons.Rounded.Celebration, stringResource(R.string.awards_last), senderName(photo), arrivalTime(photo), photo, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun senderName(photo: PhotoItem): String =
    photo.sender?.takeIf { it.isNotBlank() }?.trim() ?: stringResource(R.string.awards_anonymous)

@Composable
private fun arrivalTime(photo: PhotoItem): String {
    val millis = photo.receivedAtMillis ?: return ""
    return stringResource(R.string.awards_at, DateFormat.getTimeFormat(LocalContext.current).format(Date(millis)))
}

/** Scheda di un premio: la foto, il nome del premio, chi lo vince e un dettaglio. */
@Composable
private fun AwardCard(icon: ImageVector, award: String, name: String, detail: String, photo: PhotoItem, modifier: Modifier = Modifier) {
    Column(
        modifier.background(SurfaceDark, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            PhotoContent(photo, FitMode.CROP, Modifier.fillMaxSize(), contentDescription = null, placeholder = false)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
            Kicker(award)
        }
        Text(name, style = MaterialTheme.typography.titleLarge, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}
