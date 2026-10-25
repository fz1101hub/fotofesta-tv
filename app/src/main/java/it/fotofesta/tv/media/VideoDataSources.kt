package it.fotofesta.tv.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import it.fotofesta.tv.google.GooglePhotosStream
import it.fotofesta.tv.icloud.ICloudAlbumStream
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.network.LanHttp
import it.fotofesta.tv.network.LanResponse
import it.fotofesta.tv.smb.SmbException
import it.fotofesta.tv.smb.SmbFile
import it.fotofesta.tv.smb.SmbLocation
import it.fotofesta.tv.smb.SmbShares
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

private const val GOOGLE_SCHEME = "fotofesta-google"
private const val ICLOUD_SCHEME = "fotofesta-icloud"
private const val SMB_SCHEME = "fotofesta-smb"

/** Google rifiuta un token scaduto con 401 o 403. */
private val GOOGLE_RENEW_CODES = setOf(401, 403)

/** Apple fa scadere gli indirizzi dei file: 403, 404 o 410 secondo il server. */
private val ICLOUD_RENEW_CODES = setOf(403, 404, 410)

/** Indirizzo da dare al lettore video per un elemento della raccolta. */
fun PhotoRef.videoUri(): Uri = when (this) {
    is PhotoRef.LocalFile -> Uri.fromFile(File(path))
    is PhotoRef.LocalUri -> Uri.parse(value)
    is PhotoRef.NetworkPhoto -> Uri.parse(url)
    is PhotoRef.GooglePhoto -> Uri.Builder().scheme(GOOGLE_SCHEME).authority("media").appendPath(mediaId).build()
    is PhotoRef.ICloudPhoto -> Uri.Builder().scheme(ICLOUD_SCHEME).authority("media").appendPath(guid).build()
    // Il percorso della cartella condivisa viaggia intero in un parametro: nomi con spazi, "#" o "%" compresi.
    is PhotoRef.SmbPhoto -> Uri.Builder().scheme(SMB_SCHEME).authority("media").appendQueryParameter("path", path).build()
    is PhotoRef.Resource, is PhotoRef.GuestPhoto -> throw IllegalArgumentException("Non è un video: $this")
}

/**
 * Da dove il lettore video legge i byte: file e documenti dalla TV o dal disco USB, video della rete
 * di casa con [LanHttp] (l'unico HTTP in chiaro ammesso), video di Google Foto con il token
 * dell'utente, chiesto di nuovo a ogni apertura perché scade, video degli album condivisi di
 * iCloud dall'indirizzo che dà Apple, anch'esso rinnovato quando scade, e video delle cartelle
 * condivise (SMB) di computer e NAS.
 */
@OptIn(UnstableApi::class)
class VideoDataSources(
    private val context: Context,
    private val lan: LanHttp,
    googleStream: GooglePhotosStream,
    icloudStream: ICloudAlbumStream,
    http: OkHttpClient,
    private val smb: SmbShares,
) : DataSource.Factory {
    private val local = DefaultDataSource.Factory(context)

    // Token o indirizzo rifiutati a metà video (scaduti prima del previsto): si rinnovano una volta,
    // come per le foto, passando a GooglePhotosStream e ICloudAlbumStream quello che non ha funzionato.
    private val google = RenewingDataSource.Factory<GooglePhotosStream.Access>(OkHttpDataSource.Factory(http), GOOGLE_RENEW_CODES) { spec, failed ->
        if (spec.uri.scheme != GOOGLE_SCHEME) throw IOException("Indirizzo inatteso: ${spec.uri}")
        val mediaId = spec.uri.lastPathSegment ?: throw IOException("Video Google senza identificativo")
        val access = try {
            runBlocking { googleStream.access(mediaId, failed) }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Video Google non disponibile", failure)
        }
        // "=dv" chiede il file del video; senza, Google manderebbe un fotogramma.
        val resolved = spec.withUri(Uri.parse(access.baseUrl + "=dv"))
            .withAdditionalHeaders(mapOf("Authorization" to "Bearer ${access.accessToken}"))
        resolved to access
    }

    private val icloud = RenewingDataSource.Factory<String>(OkHttpDataSource.Factory(http), ICLOUD_RENEW_CODES) { spec, failed ->
        if (spec.uri.scheme != ICLOUD_SCHEME) throw IOException("Indirizzo inatteso: ${spec.uri}")
        val guid = spec.uri.lastPathSegment ?: throw IOException("Video iCloud senza identificativo")
        val url = try {
            runBlocking { icloudStream.mediaUrl(guid, failed) }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Video iCloud non disponibile", failure)
        }
        spec.withUri(Uri.parse(url)) to url
    }

    override fun createDataSource(): DataSource = RoutingDataSource { uri ->
        when (uri.scheme?.lowercase()) {
            "http" -> LanDataSource(lan)
            GOOGLE_SCHEME -> google.createDataSource()
            ICLOUD_SCHEME -> icloud.createDataSource()
            SMB_SCHEME -> SmbDataSource(smb)
            else -> local.createDataSource()
        }
    }
}

/**
 * Sorgente HTTP il cui indirizzo (e intestazioni) dipende da una credenziale che scade: [resolve]
 * la ottiene all'apertura e, se il server risponde con uno dei [renewCodes], la riceve indietro come
 * "non funzionante" per rinnovarla; si riprova una volta sola.
 */
@OptIn(UnstableApi::class)
internal class RenewingDataSource<T : Any>(
    private val upstream: DataSource,
    private val renewCodes: Set<Int>,
    private val resolve: (spec: DataSpec, failed: T?) -> Pair<DataSpec, T>,
) : DataSource {
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val (first, credential) = resolve(dataSpec, null)
        val length = try {
            upstream.open(first)
        } catch (refused: HttpDataSource.InvalidResponseCodeException) {
            if (refused.responseCode !in renewCodes) throw refused
            runCatching { upstream.close() }
            val (renewed, _) = resolve(dataSpec, credential)
            upstream.open(renewed)
        }
        opened = true
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = if (opened) upstream.uri else null

    override fun getResponseHeaders(): Map<String, List<String>> = if (opened) upstream.responseHeaders else emptyMap()

    override fun close() {
        opened = false
        upstream.close()
    }

    class Factory<T : Any>(
        private val upstream: DataSource.Factory,
        private val renewCodes: Set<Int>,
        private val resolve: (spec: DataSpec, failed: T?) -> Pair<DataSpec, T>,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RenewingDataSource(upstream.createDataSource(), renewCodes, resolve)
    }
}

/** Sceglie la sorgente giusta per ogni indirizzo all'apertura. */
@OptIn(UnstableApi::class)
private class RoutingDataSource(private val sourceFor: (Uri) -> DataSource) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        current?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = sourceFor(dataSpec.uri)
        listeners.forEach(source::addTransferListener)
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw IOException("Sorgente non aperta")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}

/**
 * Video di un server della rete di casa, con le richieste "Range" che servono per spostarsi nel file
 * (molti MP4 hanno l'indice alla fine).
 */
@OptIn(UnstableApi::class)
private class LanDataSource(private val http: LanHttp) : BaseDataSource(/* isNetwork = */ true) {
    private var response: LanResponse? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val bounded = dataSpec.length != C.LENGTH_UNSET.toLong()
        val headers = if (dataSpec.position > 0 || bounded) {
            val end = if (bounded) (dataSpec.position + dataSpec.length - 1).toString() else ""
            mapOf("Range" to "bytes=${dataSpec.position}-$end")
        } else {
            emptyMap()
        }
        val opening = try {
            http.get(dataSpec.uri.toString(), headers)
        } catch (failure: IOException) {
            throw DataSourceException(failure, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (!opening.isSuccessful) {
            opening.close()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        }
        val length = opening.headers["content-length"]?.trim()?.toLongOrNull()
        // Server senza Range: risponde 200 con il file intero e si salta fino al punto chiesto.
        if (opening.status == 200 && dataSpec.position > 0) {
            try {
                skipFully(opening, dataSpec.position)
            } catch (failure: IOException) {
                opening.close()
                throw DataSourceException(failure, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
        }
        remaining = when {
            bounded -> dataSpec.length
            length == null -> C.LENGTH_UNSET.toLong()
            opening.status == 200 -> length - dataSpec.position
            else -> length
        }
        response = opening
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    private fun skipFully(response: LanResponse, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val read = response.body.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw IOException("File più corto del previsto")
            left -= read
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val read = try {
            response?.body?.read(buffer, offset, toRead) ?: return C.RESULT_END_OF_INPUT
        } catch (failure: IOException) {
            throw DataSourceException(failure, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (read < 0) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            response?.close()
        } finally {
            response = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/**
 * Video di una cartella condivisa (SMB): si legge da qualunque punto, come un file locale. Le letture
 * passano da un blocco di 256 KB, perché ogni richiesta al computer è un viaggio in rete e il lettore
 * chiede spesso pochi byte alla volta.
 */
@OptIn(UnstableApi::class)
private class SmbDataSource(private val shares: SmbShares) : BaseDataSource(/* isNetwork = */ true) {
    private var file: SmbFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    private val block = ByteArray(BLOCK_BYTES)
    private var blockStart = 0L
    private var blockLength = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val location = dataSpec.uri.getQueryParameter("path")?.let(SmbLocation::parse)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val opening = try {
            shares.open(location)
        } catch (failure: SmbException) {
            val code = when (failure.reason) {
                SmbException.Reason.NOT_FOUND -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                SmbException.Reason.LOGIN, SmbException.Reason.DENIED -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                SmbException.Reason.UNREACHABLE, SmbException.Reason.NOT_LOCAL -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            }
            throw DataSourceException(failure, code)
        }
        if (dataSpec.position > opening.size) {
            opening.close()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        file = opening
        position = dataSpec.position
        blockLength = 0
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, opening.size - position)
        } else {
            opening.size - position
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val current = file ?: return C.RESULT_END_OF_INPUT
        if (position < blockStart || position >= blockStart + blockLength) {
            val read = try {
                current.read(position, block, 0, minOf(BLOCK_BYTES.toLong(), current.size - position).toInt())
            } catch (failure: IOException) {
                throw DataSourceException(failure, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
            if (read <= 0) return C.RESULT_END_OF_INPUT
            blockStart = position
            blockLength = read
        }
        val inBlock = (position - blockStart).toInt()
        val count = minOf(length.toLong(), remaining, (blockLength - inBlock).toLong()).toInt()
        System.arraycopy(block, inBlock, buffer, offset, count)
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } finally {
            file = null
            blockLength = 0
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private companion object {
        const val BLOCK_BYTES = 256 * 1024
    }
}
