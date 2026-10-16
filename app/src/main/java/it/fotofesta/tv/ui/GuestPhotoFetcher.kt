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
 * I byte si leggono subito e con il lock dell'archivio: una foto cancellata o rinominata a metà
 * caricamento dà un errore o la foto intera, mai un'immagine rotta.
 */
class GuestPhotoFetcher(
    private val photo: PhotoRef.GuestPhoto,
    private val options: Options,
    private val store: GuestPhotoStore,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = store.read(photo.id) ?: throw IOException("Foto dell'invitato non più disponibile")
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = null,
            dataSource = DataSource.MEMORY,
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
