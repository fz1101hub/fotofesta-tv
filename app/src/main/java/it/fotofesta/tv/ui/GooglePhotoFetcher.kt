package it.fotofesta.tv.ui

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import coil.size.Scale
import it.fotofesta.tv.google.GooglePhotosStream
import it.fotofesta.tv.google.HttpStatusException
import it.fotofesta.tv.google.await
import it.fotofesta.tv.model.PhotoRef
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Carica una foto di Google Foto direttamente da Google, già ridotta alla dimensione mostrata.
 * I byte passano solo in memoria: Coil non salva su disco quello che arriva da un Fetcher
 * personalizzato, e la cache in RAM si svuota da sola.
 */
class GooglePhotoFetcher(
    private val photo: PhotoRef.GooglePhoto,
    private val options: Options,
    private val stream: GooglePhotosStream,
    private val client: OkHttpClient,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val width = (options.size.width as? Dimension.Pixels)?.px?.coerceIn(MIN_SIDE, MAX_WIDTH) ?: DEFAULT_WIDTH
        val height = (options.size.height as? Dimension.Pixels)?.px?.coerceIn(MIN_SIDE, MAX_HEIGHT) ?: DEFAULT_HEIGHT
        val crop = options.scale == Scale.FILL
        var response = download(stream.access(photo.mediaId), width, height, crop)
        if (response.code == 401 || response.code == 403) {
            // Indirizzo o token scaduti prima del previsto: si rinnovano una volta.
            response.close()
            response = download(stream.access(photo.mediaId, stale = true), width, height, crop)
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw HttpStatusException(code, null, null)
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("Foto vuota")
        }
        return SourceResult(
            source = ImageSource(body.source(), options.context),
            mimeType = body.contentType()?.toString(),
            dataSource = DataSource.NETWORK,
        )
    }

    private suspend fun download(access: GooglePhotosStream.Access, width: Int, height: Int, crop: Boolean): Response {
        val request = Request.Builder()
            .url(GooglePhotosStream.sizedUrl(access.baseUrl, width, height, crop))
            .header("Authorization", "Bearer ${access.accessToken}")
            .build()
        return client.newCall(request).await()
    }

    class Factory(
        private val stream: GooglePhotosStream,
        private val client: OkHttpClient,
    ) : Fetcher.Factory<PhotoRef.GooglePhoto> {
        override fun create(data: PhotoRef.GooglePhoto, options: Options, imageLoader: ImageLoader): Fetcher =
            GooglePhotoFetcher(data, options, stream, client)
    }

    /** Chiave della cache in RAM: stabile anche quando Google cambia l'indirizzo della foto. */
    class Key : Keyer<PhotoRef.GooglePhoto> {
        override fun key(data: PhotoRef.GooglePhoto, options: Options): String =
            "google:${data.mediaId}:${options.scale}"
    }

    private companion object {
        const val MIN_SIDE = 64
        const val MAX_WIDTH = 3840
        const val MAX_HEIGHT = 2160
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
    }
}
