package it.fotofesta.tv.media

import java.io.File
import java.io.RandomAccessFile

/**
 * Foto in movimento di Pixel e Samsung: un JPEG con un breve video MP4 attaccato in fondo. Il
 * Pixel scrive nei metadati XMP quanto è lungo il video ("MicroVideoOffset" o "Item:Length" del
 * contenitore MotionPhoto), Samsung lo fa precedere da "MotionPhoto_Data". Qui si trova dove
 * comincia il video, senza Android, leggendo solo l'inizio e la fine del file.
 */
object MotionPhoto {
    /** Il video dentro la foto: da [offset] per [length] byte. */
    data class Clip(val offset: Long, val length: Long)

    fun find(file: File): Clip? = runCatching {
        RandomAccessFile(file, "r").use { access ->
            val size = access.length()
            if (size < 1024) return null
            val head = ByteArray(minOf(size, HEAD_BYTES.toLong()).toInt()).also { access.readFully(it) }
            val tailStart = maxOf(0L, size - TAIL_BYTES)
            val tail = ByteArray((size - tailStart).toInt()).also { access.seek(tailStart); access.readFully(it) }
            find(head, tail, tailStart, size)
        }
    }.getOrNull()

    /** [head] è l'inizio del file, [tail] la parte da [tailStart] alla fine ([size] byte in tutto). */
    fun find(head: ByteArray, tail: ByteArray, tailStart: Long, size: Long): Clip? {
        if (head.size < 2 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return null
        val xmp = String(head, Charsets.ISO_8859_1)
        // Pixel, formato vecchio: GCamera:MicroVideoOffset="123456" (byte dalla fine).
        Regex("MicroVideoOffset=\"(\\d+)\"").find(xmp)?.groupValues?.get(1)?.toLongOrNull()?.let { length ->
            clipFromEnd(length, size)?.let { return it }
        }
        // Formato MotionPhoto: l'elemento con Item:Semantic="MotionPhoto" ha Item:Length.
        Regex("Item:Semantic=\"MotionPhoto\"[^>]*?Item:Length=\"(\\d+)\"|Item:Length=\"(\\d+)\"[^>]*?Item:Semantic=\"MotionPhoto\"")
            .find(xmp)?.groupValues?.let { groups -> (groups[1].ifEmpty { groups[2] }).toLongOrNull() }?.let { length ->
                clipFromEnd(length, size)?.let { return it }
            }
        // Samsung: il marcatore "MotionPhoto_Data" e subito dopo il video.
        indexOf(tail, SAMSUNG_MARKER)?.let { index ->
            val offset = tailStart + index + SAMSUNG_MARKER.size
            if (offset < size && isMp4(tail, index + SAMSUNG_MARKER.size)) return Clip(offset, size - offset)
        }
        return null
    }

    private fun clipFromEnd(length: Long, size: Long): Clip? =
        if (length in MIN_VIDEO..(size - 1024)) Clip(size - length, length) else null

    /** Un MP4 comincia con la dimensione del box e "ftyp". */
    private fun isMp4(bytes: ByteArray, at: Int): Boolean =
        at + 8 <= bytes.size && bytes[at + 4] == 'f'.code.toByte() && bytes[at + 5] == 't'.code.toByte() &&
            bytes[at + 6] == 'y'.code.toByte() && bytes[at + 7] == 'p'.code.toByte()

    private fun indexOf(bytes: ByteArray, pattern: ByteArray): Int? {
        outer@ for (i in 0..bytes.size - pattern.size) {
            for (j in pattern.indices) if (bytes[i + j] != pattern[j]) continue@outer
            return i
        }
        return null
    }

    /** Copia il video in [target] (per il lettore video); false se non c'è. */
    fun extract(file: File, target: File): Boolean {
        val clip = find(file) ?: return false
        if (clip.length > MAX_VIDEO) return false
        RandomAccessFile(file, "r").use { access ->
            access.seek(clip.offset)
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var left = clip.length
                while (left > 0) {
                    val read = access.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    left -= read
                }
            }
        }
        return true
    }

    private const val HEAD_BYTES = 128 * 1024
    private const val TAIL_BYTES = 8L * 1024 * 1024
    private const val MIN_VIDEO = 4_096L
    private const val MAX_VIDEO = 40L * 1024 * 1024
    private val SAMSUNG_MARKER = "MotionPhoto_Data".toByteArray(Charsets.ISO_8859_1)
}
