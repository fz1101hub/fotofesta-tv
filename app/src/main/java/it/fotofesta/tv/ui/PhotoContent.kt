package it.fotofesta.tv.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
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
    is PhotoRef.Remote -> url
    // Caricata da GooglePhotoFetcher, registrato nell'ImageLoader dell'app.
    is PhotoRef.GooglePhoto -> this
}

@Composable
fun PhotoContent(
    photo: PhotoItem,
    fitMode: FitMode,
    modifier: Modifier = Modifier,
    contentDescription: String? = photo.title,
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
            is AsyncImagePainter.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is AsyncImagePainter.State.Error -> UnavailablePhoto()
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
