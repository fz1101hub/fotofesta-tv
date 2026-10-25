package it.fotofesta.tv.ui

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.smb.SmbException
import it.fotofesta.tv.smb.SmbLocation
import it.fotofesta.tv.smb.SmbShares
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

/**
 * Carica una foto da una cartella condivisa (SMB) di un computer o NAS, leggendo il file dall'inizio
 * alla fine. Come le foto in rete, non si salva nulla su disco.
 */
class SmbPhotoFetcher(
    private val photo: PhotoRef.SmbPhoto,
    private val options: Options,
    private val shares: SmbShares,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val location = SmbLocation.parse(photo.path) ?: throw SmbException(SmbException.Reason.NOT_FOUND)
        val file = shares.open(location)
        SourceResult(
            source = ImageSource(file.inputStream().source().buffer(), options.context),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val shares: SmbShares) : Fetcher.Factory<PhotoRef.SmbPhoto> {
        override fun create(data: PhotoRef.SmbPhoto, options: Options, imageLoader: ImageLoader): Fetcher =
            SmbPhotoFetcher(data, options, shares)
    }

    class Key : Keyer<PhotoRef.SmbPhoto> {
        override fun key(data: PhotoRef.SmbPhoto, options: Options): String = "smb:${data.path}"
    }
}
