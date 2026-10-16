package it.fotofesta.tv.ui

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import it.fotofesta.tv.guests.GuestPhotoStore
import it.fotofesta.tv.model.PhotoRef
import okio.Buffer
import java.io.IOException

/**
 * Carica una foto mandata da un invitato dall'archivio della festa (RAM o cartella di riserva).
 * I byte si leggono subito: una foto cancellata a metà caricamento dà un errore, non un'immagine rotta.
 */
class GuestPhotoFetcher(
    private val photo: PhotoRef.GuestPhoto,
    private val options: Options,
    private val store: GuestPhotoStore,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val (bytes, dataSource) = when (val content = store.open(photo.id)) {
            is GuestPhotoStore.Content.Bytes -> content.value to DataSource.MEMORY
            is GuestPhotoStore.Content.OnDisk -> content.file.readBytes() to DataSource.DISK
            null -> throw IOException("Foto dell'invitato non più disponibile")
        }
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = null,
            dataSource = dataSource,
        )
    }

    class Factory(private val store: GuestPhotoStore) : Fetcher.Factory<PhotoRef.GuestPhoto> {
        override fun create(data: PhotoRef.GuestPhoto, options: Options, imageLoader: ImageLoader): Fetcher =
            GuestPhotoFetcher(data, options, store)
    }

    /** L'immagine è decodificata da Coil con il campionamento, quindi la cache in RAM sa quando riusarla. */
    class Key : Keyer<PhotoRef.GuestPhoto> {
        override fun key(data: PhotoRef.GuestPhoto, options: Options): String = "guest:${data.id}"
    }
}
