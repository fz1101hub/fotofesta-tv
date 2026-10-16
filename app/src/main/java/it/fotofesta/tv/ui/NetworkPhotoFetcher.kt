package it.fotofesta.tv.ui

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import it.fotofesta.tv.google.HttpStatusException
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.network.LanHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

/**
 * Carica una foto da un server multimediale della rete di casa con [LanHttp], che parla in chiaro
 * solo con indirizzi locali. Come le foto Google, non si salva nulla su disco.
 */
class NetworkPhotoFetcher(
    private val photo: PhotoRef.NetworkPhoto,
    private val options: Options,
    private val http: LanHttp,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val response = http.get(photo.url)
        if (!response.isSuccessful) {
            response.close()
            throw HttpStatusException(response.status, null, null)
        }
        SourceResult(
            source = ImageSource(response.body.source().buffer(), options.context),
            mimeType = response.headers["content-type"]?.substringBefore(';')?.trim()?.takeIf { it.startsWith("image/") },
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val http: LanHttp) : Fetcher.Factory<PhotoRef.NetworkPhoto> {
        override fun create(data: PhotoRef.NetworkPhoto, options: Options, imageLoader: ImageLoader): Fetcher =
            NetworkPhotoFetcher(data, options, http)
    }

    /** L'immagine arriva intera e Coil la decodifica con il campionamento: la cache in RAM sa quando riusarla. */
    class Key : Keyer<PhotoRef.NetworkPhoto> {
        override fun key(data: PhotoRef.NetworkPhoto, options: Options): String = "net:${data.url}"
    }
}
