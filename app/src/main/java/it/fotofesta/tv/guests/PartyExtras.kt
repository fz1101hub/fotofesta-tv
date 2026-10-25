package it.fotofesta.tv.guests

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Pagine in più del server della festa, oltre a quella dei telefoni: il QR da mettere all'ingresso
 * ("sono arrivato") e la parete delle foto per un secondo schermo (Echo Show, tablet, PC).
 * Tutte con la stessa chiave del QR della festa.
 */
interface PartyExtras {
    /** Pagina da stampare o da lasciare su un tablet all'ingresso, con il QR degli arrivi. */
    fun doorPage(): String

    /** Parete delle foto: slideshow delle foto approvate degli invitati, dediche e QR. */
    fun wallPage(): String

    /** Stato per la parete: ultime foto approvate, dediche, annuncio, titolo della festa. */
    fun wallState(): String

    /** Foto approvata [id] per la parete; null se non c'è o non è approvata. */
    fun wallPhoto(id: String): ByteArray?

    /** Silent disco per la pagina dei telefoni, in JSON; "null" se è spenta. */
    fun discoJson(): String = "null"

    /** Brano [id] della silent disco, solo se è quello che sta suonando. */
    fun discoTrack(id: String): DiscoTrack? = null

    /** Foto approvate da portare a casa in un archivio; null se chi organizza non lo permette. */
    fun album(): List<ZipStream.Entry>? = null

    /** Muro di telefoni per il telefono [player], in JSON; "null" se è spento. */
    fun phoneWallJson(player: String?): String = "null"

    /** Iscrizione al muro di telefoni: stato HTTP e risposta; null se la TV non lo offre. */
    fun phoneWallAction(form: Map<String, String>): Pair<Int, String>? = null
}

/** Brano da mandare ai telefoni della silent disco: lunghezza, tipo e lettura da una posizione. */
class DiscoTrack(val length: Long, val mimeType: String, val open: (offset: Long) -> java.io.InputStream)

/**
 * Intervallo "Range: bytes=a-b" per l'audio dei telefoni (Safari lo chiede sempre). Restituisce
 * l'intervallo da mandare, al massimo [maxChunk] byte: il telefono chiede da solo il resto.
 * Null se l'intestazione non vale per questo file.
 */
fun discoRange(header: String?, length: Long, maxChunk: Long): LongRange? {
    if (length <= 0) return null
    if (header == null) return 0L..minOf(length, maxChunk) - 1
    val spec = header.trim().removePrefix("bytes=").takeIf { header.trim().startsWith("bytes=") && ',' !in it } ?: return null
    val startText = spec.substringBefore('-').trim()
    val endText = spec.substringAfter('-', "").trim()
    val (start, end) = if (startText.isEmpty()) {
        // "bytes=-500": gli ultimi 500 byte.
        val suffix = endText.toLongOrNull()?.takeIf { it > 0 } ?: return null
        (length - suffix).coerceAtLeast(0) to length - 1
    } else {
        val first = startText.toLongOrNull() ?: return null
        val last = endText.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
        first to last
    }
    if (start < 0 || start >= length || end < start) return null
    return start..minOf(end, start + maxChunk - 1)
}

/** QR in SVG, per le pagine servite ai telefoni e agli altri schermi (nessuna immagine da generare). */
object QrSvg {
    fun encode(value: String, margin: Int = 2): String {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = matrix.width + margin * 2
        val path = buildString {
            for (y in 0 until matrix.height) {
                var x = 0
                while (x < matrix.width) {
                    if (!matrix[x, y]) { x++; continue }
                    // Moduli neri consecutivi della riga in un solo rettangolo: l'SVG resta piccolo.
                    val start = x
                    while (x < matrix.width && matrix[x, y]) x++
                    append('M').append(start + margin).append(' ').append(y + margin)
                    append('h').append(x - start).append("v1h-").append(x - start).append('z')
                }
            }
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" shape-rendering="crispEdges">""" +
            """<rect width="$size" height="$size" fill="#fff"/><path fill="#000" d="$path"/></svg>"""
    }
}
