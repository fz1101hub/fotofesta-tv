package it.fotofesta.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.fotofesta.tv.R
import it.fotofesta.tv.guests.PhoneWall
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem

/**
 * Muro di telefoni sulla TV: come disporre i telefoni (con i numeri che mostrano) e, a foto
 * mandata, l'anteprima della foto divisa tra i telefoni.
 */
@Composable
fun PhoneWallScreen(
    wall: PhoneWall.State?,
    photo: PhotoItem?,
    onShow: () -> Unit,
    onRearrange: () -> Unit,
    onBack: () -> Unit,
) {
    val count = wall?.players?.size ?: 0
    val showing = wall?.phase == PhoneWall.Phase.SHOWING
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.phone_wall_kicker),
                stringResource(R.string.phone_wall_title),
                stringResource(R.string.phone_wall_subtitle),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(260.dp), contentAlignment = Alignment.Center) {
                if (wall != null && count > 0) PhoneGrid(wall, photo.takeIf { showing })
                else Text(stringResource(R.string.phone_wall_waiting), style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            }
            Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Kicker(quantityStringResource(R.plurals.phone_wall_count, count))
                TvButton(
                    stringResource(if (showing) R.string.phone_wall_another else R.string.phone_wall_show),
                    onShow,
                    modifier = Modifier.initialFocus(),
                    icon = Icons.Rounded.Image,
                    enabled = count > 0,
                )
                if (showing) TvButton(stringResource(R.string.phone_wall_rearrange), onRearrange, icon = Icons.Rounded.Refresh, ghost = true)
                Text(stringResource(R.string.phone_wall_tip), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

/** Telefoni in verticale, in file e colonne come sul tavolo; con la foto, ognuno ne mostra il suo pezzo. */
@Composable
private fun PhoneGrid(wall: PhoneWall.State, photo: PhotoItem?) {
    val ratio = wall.columns * PHONE_WIDTH / (wall.rows * PHONE_HEIGHT)
    Box(Modifier.aspectRatio(ratio).clip(RoundedCornerShape(10.dp))) {
        if (photo != null) PhotoContent(photo, FitMode.CROP, Modifier.fillMaxSize(), contentDescription = null, placeholder = false)
        Column(Modifier.fillMaxSize()) {
            repeat(wall.rows) { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    repeat(wall.columns) { column ->
                        val slot = row * wall.columns + column
                        val taken = slot < wall.players.size
                        Box(
                            Modifier.weight(1f).fillMaxSize().padding(3.dp)
                                .border(3.dp, if (taken) Color.White else Outline, RoundedCornerShape(10.dp))
                                .background(if (photo == null) SurfaceDark else Color.Transparent, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (taken && photo == null) {
                                Text((slot + 1).toString(), color = TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val PHONE_WIDTH = 9f
private const val PHONE_HEIGHT = 19f
