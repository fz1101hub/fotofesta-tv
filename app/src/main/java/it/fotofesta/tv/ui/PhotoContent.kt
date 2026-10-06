package it.fotofesta.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PhotoRef
import java.io.File

@Composable
fun PhotoContent(photo: PhotoItem, fitMode: FitMode, modifier: Modifier = Modifier) {
    val scale = if (fitMode == FitMode.CROP) ContentScale.Crop else ContentScale.Fit
    when (val ref = photo.ref) {
        is PhotoRef.Resource -> Image(
            painter = androidx.compose.ui.res.painterResource(ref.drawable),
            contentDescription = photo.title,
            contentScale = scale,
            modifier = modifier,
        )
        is PhotoRef.LocalUri -> NetworkOrLocalImage(ref.value, photo.title, scale, modifier)
        is PhotoRef.LocalFile -> NetworkOrLocalImage(File(ref.path), photo.title, scale, modifier)
        is PhotoRef.Remote -> NetworkOrLocalImage(ref.url, photo.title, scale, modifier)
    }
}

@Composable
private fun NetworkOrLocalImage(model: Any, description: String, scale: ContentScale, modifier: Modifier) {
    SubcomposeAsyncImage(
        model = model,
        contentDescription = description,
        contentScale = scale,
        modifier = modifier,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> SubcomposeAsyncImageContent()
        }
    }
}
