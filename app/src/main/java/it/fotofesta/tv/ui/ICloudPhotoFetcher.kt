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
import it.fotofesta.tv.google.await
import it.fotofesta.tv.icloud.ICloudAlbumStream
import it.fotofesta.tv.model.PhotoRef
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Carica una foto di un album condiviso di iCloud dall'indirizzo che dà Apple; se l'indirizzo è
 * scaduto lo rinnova una volta. Come per Google Foto, non si salva nulla su disco.
 */
class ICloudPhotoFetcher(
    private val photo: PhotoRef.ICloudPhoto,
    private val options: Options,
    private val stream: ICloudAlbumStream,
    private val client: OkHttpClient,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val first = stream.mediaUrl(photo.guid)
        var response = client.newCall(Request.Builder().url(first).build()).await()
        if (response.code == 403 || response.code == 404 || response.code == 410) {
            response.close()
            response = client.newCall(Request.Builder().url(stream.mediaUrl(photo.guid, failed = first)).build()).await()
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

    class Factory(private val stream: ICloudAlbumStream, private val client: OkHttpClient) : Fetcher.Factory<PhotoRef.ICloudPhoto> {
        override fun create(data: PhotoRef.ICloudPhoto, options: Options, imageLoader: ImageLoader): Fetcher =
            ICloudPhotoFetcher(data, options, stream, client)
    }

    /** La foto arriva intera e Coil la decodifica con il campionamento: la cache in RAM sa quando riusarla. */
    class Key : Keyer<PhotoRef.ICloudPhoto> {
        override fun key(data: PhotoRef.ICloudPhoto, options: Options): String = "icloud:${data.guid}"
    }
}
