package it.fotofesta.tv.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import coil.size.Precision
import it.fotofesta.tv.R
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import java.io.File

/** Modello da passare a Coil per la foto; deve coincidere ovunque perché il precaricamento funzioni. */
fun PhotoRef.coilModel(): Any = when (this) {
    is PhotoRef.Resource -> drawable
    is PhotoRef.LocalUri -> value
    is PhotoRef.LocalFile -> File(path)
    // Caricate da GooglePhotoFetcher, GuestPhotoFetcher e NetworkPhotoFetcher, registrati
    // nell'ImageLoader dell'app.
    is PhotoRef.GooglePhoto -> this
    is PhotoRef.GuestPhoto -> this
    is PhotoRef.NetworkPhoto -> this
    is PhotoRef.ICloudPhoto -> this
}

@Composable
fun PhotoContent(
    photo: PhotoItem,
    fitMode: FitMode,
    modifier: Modifier = Modifier,
    contentDescription: String? = photo.title,
    /** Con false, durante il caricamento e in caso di errore non si mostra nulla (sfondi). */
    placeholder: Boolean = true,
) {
    val scale = if (fitMode == FitMode.CROP) ContentScale.Crop else ContentScale.Fit
    // Anche le foto demo passano da Coil: decodifica fuori dal thread principale e ridotta alla
    // dimensione mostrata (una 2560×1440 intera occuperebbe 15 MB per ogni carta del Memory).
    SubcomposeAsyncImage(
        model = photo.ref.coilModel(),
        contentDescription = contentDescription,
        contentScale = scale,
        modifier = modifier,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Loading -> if (placeholder) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TextSecondary)
                }
            } else {
                Unit
            }
            is AsyncImagePainter.State.Error -> if (placeholder) UnavailablePhoto() else Unit
            else -> SubcomposeAsyncImageContent()
        }
    }
}

@Composable
private fun UnavailablePhoto() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.BrokenImage, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(56.dp))
            Text(stringResource(R.string.photo_unavailable), style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        }
    }
}

/**
 * Sfondo per le foto intere: la stessa foto, ingrandita e sfocata, scurita perché la foto davanti
 * risalti. Si decodifica in miniatura (pochi kB) e si allarga: già così è sfocata, e da Android 12
 * una sfocatura vera la rende uniforme.
 */
@Composable
fun BlurredBackdrop(photo: PhotoItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(photo.id) {
        ImageRequest.Builder(context)
            .data(photo.ref.coilModel())
            .size(BACKDROP_SIZE_PX)
            .precision(Precision.EXACT)
            // Chiave propria: la miniatura non deve finire al posto della foto a piena risoluzione.
            .memoryCacheKey("backdrop:${photo.id}")
            .build()
    }
    Box(modifier.clipToBounds()) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer {
                    // Leggermente più grande: i bordi sfocati non mostrano lo sfondo nero.
                    scaleX = 1.12f
                    scaleY = 1.12f
                }
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(36.dp) else Modifier),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)))
    }
}

private const val BACKDROP_SIZE_PX = 48
