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
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import it.fotofesta.tv.google.GooglePhotosStream
import it.fotofesta.tv.icloud.ICloudAlbumStream
import it.fotofesta.tv.model.PhotoRef
import it.fotofesta.tv.network.LanHttp
import it.fotofesta.tv.network.LanResponse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

private const val GOOGLE_SCHEME = "fotofesta-google"
private const val ICLOUD_SCHEME = "fotofesta-icloud"

/** Indirizzo da dare al lettore video per un elemento della raccolta. */
fun PhotoRef.videoUri(): Uri = when (this) {
    is PhotoRef.LocalFile -> Uri.fromFile(File(path))
    is PhotoRef.LocalUri -> Uri.parse(value)
    is PhotoRef.NetworkPhoto -> Uri.parse(url)
    is PhotoRef.GooglePhoto -> Uri.Builder().scheme(GOOGLE_SCHEME).authority("media").appendPath(mediaId).build()
    is PhotoRef.ICloudPhoto -> Uri.Builder().scheme(ICLOUD_SCHEME).authority("media").appendPath(guid).build()
    is PhotoRef.Resource, is PhotoRef.GuestPhoto -> throw IllegalArgumentException("Non è un video: $this")
}

/**
 * Da dove il lettore video legge i byte: file e documenti dalla TV o dal disco USB, video della rete
 * di casa con [LanHttp] (l'unico HTTP in chiaro ammesso), video di Google Foto con il token
 * dell'utente, chiesto di nuovo a ogni apertura perché scade, e video degli album condivisi di
 * iCloud dall'indirizzo che dà Apple, anch'esso rinnovato quando scade.
 */
@OptIn(UnstableApi::class)
class VideoDataSources(
    private val context: Context,
    private val lan: LanHttp,
    googleStream: GooglePhotosStream,
    icloudStream: ICloudAlbumStream,
    http: OkHttpClient,
) : DataSource.Factory {
    private val local = DefaultDataSource.Factory(context)

    private val google = ResolvingDataSource.Factory(OkHttpDataSource.Factory(http)) { spec ->
        if (spec.uri.scheme != GOOGLE_SCHEME) return@Factory spec
        val mediaId = spec.uri.lastPathSegment ?: throw IOException("Video Google senza identificativo")
        val access = try {
            runBlocking { googleStream.access(mediaId) }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Video Google non disponibile", failure)
        }
        // "=dv" chiede il file del video; senza, Google manderebbe un fotogramma.
        spec.withUri(Uri.parse(access.baseUrl + "=dv"))
            .withAdditionalHeaders(mapOf("Authorization" to "Bearer ${access.accessToken}"))
    }

    private val icloud = ResolvingDataSource.Factory(OkHttpDataSource.Factory(http)) { spec ->
        if (spec.uri.scheme != ICLOUD_SCHEME) return@Factory spec
        val guid = spec.uri.lastPathSegment ?: throw IOException("Video iCloud senza identificativo")
        val url = try {
            runBlocking { icloudStream.mediaUrl(guid) }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Video iCloud non disponibile", failure)
        }
        spec.withUri(Uri.parse(url))
    }

    override fun createDataSource(): DataSource = RoutingDataSource { uri ->
        when (uri.scheme?.lowercase()) {
            "http" -> LanDataSource(lan)
            GOOGLE_SCHEME -> google.createDataSource()
            ICLOUD_SCHEME -> icloud.createDataSource()
            else -> local.createDataSource()
        }
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
